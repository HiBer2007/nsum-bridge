/*
 * NSUM Bridge — HMCL integration for NeoServerUpdateModpack
 * Copyright (C) 2026 HiBERNET
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package com.hibernet.flfmod.nsum.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.task.FileDownloadTask;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;
import org.jackhuang.hmcl.util.TaskCancellationAction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/// NSUM Bridge 自身的自动更新。
///
/// <p>从 metadata 站读取 `nsum-bridge` 的最新版本，与当前 jar 版本比较；
/// 发现新版本时提示用户，下载（SHA-256 校验）到暂存目录并尝试替换正在使用的
/// jar。替换成功后提示重启 HMCL 生效；替换失败（jar 被占用）时给出暂存路径与手动步骤。</p>
public final class NSumBridgeUpdater {

    private static final Logger LOG = LogManager.getLogger(NSumBridgeUpdater.class);

    private NSumBridgeUpdater() {
    }

    public record UpdateInfo(String version, String downloadLink, String sha256, String releasePage) {
    }

    /// 当前 jar 版本：直接从本 mod 的 jar 里读 `fabric.mod.json`。
    ///
    /// <p>不能用 `getResourceAsStream("/fabric.mod.json")`：多个 mod 在同一 classpath 时
    /// 拿到的是别人的 `fabric.mod.json`（实测读到 flf-api 的 1.2.0-hmcl3.16）。</p>
    public static String currentVersion() {
        try {
            Path self = Path.of(NSumBridgeUpdater.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(self)) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(self.toFile())) {
                    var entry = zip.getEntry("fabric.mod.json");
                    if (entry != null) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            JsonObject obj = JsonParser.parseString(
                                    new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                            if (obj.has("version")) {
                                return obj.get("version").getAsString();
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOG.debug("[NSUM] 读取 bridge 版本失败: {}", t.toString());
        }
        return "0.0.0";
    }

    /// 检查更新；无更新返回 null。
    public static UpdateInfo check() throws IOException, InterruptedException {
        NSumMetadata.Project project = NSumMetadata.latest(NSumMetadata.TYPE_BRIDGE);
        if (project == null || project.latest().isBlank()) {
            return null;
        }
        String current = currentVersion();
        if (NSumMetadata.compare(project.latest(), current) <= 0) {
            return null;
        }
        for (NSumMetadata.Entry entry : project.entries()) {
            if (project.latest().equals(entry.version())) {
                if (entry.downloadLink().isBlank()) {
                    return null;
                }
                return new UpdateInfo(entry.version(), entry.downloadLink(),
                        entry.sha256(), entry.releasePage());
            }
        }
        return null;
    }

    /// 启动时静默检查（可在配置里关闭）。
    public static void autoCheckOnStartup() {
        try {
            if (!NSumConfig.load().isAutoUpdateBridge()) {
                return;
            }
        } catch (Throwable t) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                UpdateInfo info = check();
                if (info != null) {
                    Platform.runLater(() -> prompt(info));
                }
            } catch (Throwable t) {
                LOG.debug("[NSUM] Bridge 更新检查失败: {}", t.toString());
            }
        });
    }

    /// 弹窗询问并执行更新。
    public static void prompt(UpdateInfo info) {
        String text = "当前版本：" + currentVersion() + "\n最新版本：" + info.version()
                + "\n\n是否下载并安装？安装后需要重启 HMCL 生效。";
        Controllers.confirm(text, "NSUM Bridge 有新版本", MessageDialogPane.MessageType.QUESTION,
                () -> downloadAndApply(info),
                () -> LOG.info("[NSUM] 用户取消 Bridge 更新"));
    }

    private static void downloadAndApply(UpdateInfo info) {
        String fileName = info.downloadLink().substring(info.downloadLink().lastIndexOf('/') + 1);
        if (fileName.isBlank()) {
            fileName = "nsum-bridge-" + info.version() + ".jar";
        }
        Path staging = NSumPaths.nsumDir().resolve("bridge").resolve(fileName);
        try {
            Files.createDirectories(staging.getParent());
        } catch (IOException e) {
            Controllers.dialog("无法创建下载目录：" + e.getMessage(), "NSUM Bridge",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        try {
            Files.deleteIfExists(staging);
        } catch (IOException ignored) {
        }

        Task<Void> download = info.sha256().isBlank()
                ? new FileDownloadTask(info.downloadLink(), staging)
                        .setName("下载 NSUM Bridge " + info.version())
                : new FileDownloadTask(info.downloadLink(), staging,
                        FileDownloadTask.IntegrityCheck.of("SHA-256", info.sha256()))
                        .setName("下载 NSUM Bridge " + info.version());

        Task<Void> task = download.thenComposeAsync(() -> applyTask(staging, info));
        TaskExecutor executor = task.executor();
        Controllers.taskDialog(executor, "更新 NSUM Bridge " + info.version(),
                TaskCancellationAction.NORMAL);
    }

    private static Task<Void> applyTask(Path staging, UpdateInfo info) {
        return Task.supplyAsync(() -> {
            String result = apply(staging, info);
            Platform.runLater(() -> Controllers.dialogLater(new MessageDialogPane.Builder(
                    result, "NSUM Bridge", MessageDialogPane.MessageType.INFO).build()));
            return null;
        });
    }

    /// 尝试替换当前 jar；返回给用户看的结果文本。
    static String apply(Path staging, UpdateInfo info) {
        try {
            Path current = Path.of(NSumBridgeUpdater.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(current)) {
                return "新版本已下载到：\n" + staging
                        + "\n\n请手动替换 mods 目录里的 nsum-bridge jar 后重启 HMCL。";
            }
            Path backup = current.resolveSibling(current.getFileName() + ".bak");
            Files.move(current, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(staging, current, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("[NSUM] Bridge 已更新: {} -> {}", backup, current);
            return "NSUM Bridge 已更新到 " + info.version() + "，重启 HMCL 后生效。\n\n"
                    + "旧版本备份：" + backup;
        } catch (Throwable t) {
            LOG.warn("[NSUM] 替换 Bridge jar 失败: {}", t.toString());
            return "新版本已下载到：\n" + staging
                    + "\n\n自动替换失败（" + t.getMessage() + "），请关闭 HMCL 后手动替换 mods 里的 nsum-bridge jar。";
        }
    }
}
