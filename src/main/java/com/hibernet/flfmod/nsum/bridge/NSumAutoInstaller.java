/*
 * NSUM Bridge — HMCL integration for NeoServerUpdateModpack
 * Copyright (C) 2026 HiBerNET
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package com.hibernet.flfmod.nsum.bridge;

import javafx.application.Platform;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// NSUM Runtime 的自动安装/更新，以及 bridge 自身的更新检查。
///
/// <p>数据来自元数据中心（`metadata.hibernet.top`，见 [NSumMetadata]）。
/// 启动时若 Runtime 未安装 → 弹窗询问是否安装；有新版 → 询问是否更新。</p>
public final class NSumAutoInstaller {

    private static final Logger LOG = LogManager.getLogger(NSumAutoInstaller.class);

    private NSumAutoInstaller() {
    }

    /// 启动时检查（后台线程执行网络请求，FX 线程弹窗）。
    public static void checkOnStartup() {
        CompletableFuture.runAsync(() -> {
            try {
                var project = NSumMetadata.latest(NSumMetadata.TYPE_NSUM);
                var status = NSumRuntime.status();
                if (project == null) {
                    return;
                }
                String latest = project.latest();
                if (!status.installed()) {
                    Platform.runLater(() -> promptInstall(project, latest, false));
                } else if (NSumMetadata.compare(status.version(), latest) < 0) {
                    Platform.runLater(() -> promptInstall(project, latest, true));
                }
                checkBridgeUpdate();
            } catch (Throwable t) {
                LOG.warn("[NSUM] 启动检查失败: {}", t.toString());
            }
        });
    }

    /// 从元数据安装/更新（GUI 按钮也直接调用）。
    public static void promptInstall(NSumMetadata.Project project, String version, boolean update) {
        String title = update ? "NSUM Runtime 有新版本" : "需要安装 NSUM Runtime";
        String text = update
                ? "当前版本：" + NSumRuntime.status().version() + "\n最新版本：" + version + "\n\n是否现在更新？"
                : "该功能需要 NSUM Runtime（NSUM 的 CLI 运行时）。\n最新版本：" + version + "\n\n是否现在下载并安装？";
        Controllers.confirm(text, title, MessageDialogPane.MessageType.QUESTION,
                () -> doInstall(project, version, update),
                () -> LOG.info("[NSUM] 用户取消 {} Runtime", update ? "更新" : "安装"));
    }

    private static void doInstall(NSumMetadata.Project project, String version, boolean update) {
        NSumMetadata.Entry entry = null;
        for (var candidate : project.entries()) {
            if (version.equals(candidate.version())) {
                entry = candidate;
                break;
            }
        }
        if (entry == null || entry.downloadLink().isBlank()) {
            Controllers.dialog("元数据里没有 " + version + " 的下载地址", "NSUM",
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        var finalEntry = entry;
        CompletableFuture.runAsync(() -> {
            NSumProgressPane pane = new NSumProgressPane("正在安装 NSUM Runtime " + version);
            Platform.runLater(() -> Controllers.dialog(pane));
            try {
                Path archive = download(finalEntry);
                String name = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".exe")) {
                    // HIC 安装器：CLI + JSON 进度，通过 pane 回显到 HMCL GUI；
                    // 安装流程内快捷方式步骤 enabled:false，不会创建桌面/开始菜单项。
                    pane.progress(0, "运行安装器…");
                    NSumHicInstaller.Result result = NSumHicInstaller.install(
                            archive, NSumPaths.runtimeDir(), pane);
                    if (!result.success()) {
                        throw new IOException(result.message());
                    }
                    NSumRuntime.markInstalled(version, "hic:" + archive.getFileName());
                    pane.finish("Runtime 已安装到 " + NSumPaths.runtimeDir());
                } else {
                    Path sourceDir = unpack(archive);
                    NSumRuntime.Status status = NSumRuntime.install(sourceDir, version);
                    pane.finish("Runtime 已安装到 " + status.dir());
                }
            } catch (Exception e) {
                LOG.error("[NSUM] Runtime 安装失败", e);
                pane.fail(e.getMessage());
                Platform.runLater(() -> Controllers.dialog(
                        "安装失败：" + e.getMessage()
                                + "\n\n可选办法：\n"
                                + "1) 手动下载 Runtime 安装包/压缩包，放进 .hmcl/nsum/downloads/ 后点「从 downloads 安装」；\n"
                                + "2) 在 .hmcl/nsum/config.json 里设置 runtimeSourceDir（NSUM 部署目录）后点「安装 Runtime」。",
                        "NSUM", MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// 从 `.hmcl/nsum/downloads/` 里最新的压缩包/可执行文件安装（离线/网络失败兜底）。
    public static void installFromDownloads() {
        CompletableFuture.runAsync(() -> {
            Path dir = NSumPaths.nsumDir().resolve("downloads");
            Path archive = newestArchive(dir);
            if (archive == null) {
                Platform.runLater(() -> Controllers.dialog(
                        "在 " + dir + " 里没有找到 .zip / .exe。\n"
                                + "请把 NSUM Runtime 发布包放到该目录后重试。",
                        "NSUM", MessageDialogPane.MessageType.WARNING));
                return;
            }
            try {
                Path sourceDir = unpack(archive);
                NSumRuntime.Status status = NSumRuntime.install(sourceDir, "local:" + archive.getFileName());
                Platform.runLater(() -> Controllers.dialog(
                        "已从本地压缩包安装：" + archive.getFileName() + "\n目录：" + status.dir(),
                        "NSUM", MessageDialogPane.MessageType.SUCCESS));
            } catch (Exception e) {
                LOG.error("[NSUM] 本地压缩包安装失败", e);
                Platform.runLater(() -> Controllers.dialog(
                        "安装失败：" + e.getMessage(),
                        "NSUM", MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static Path newestArchive(Path dir) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".zip") || name.endsWith(".exe");
                    })
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            LOG.warn("[NSUM] 扫描 downloads 失败: {}", e.toString());
            return null;
        }
    }

    private static Path download(NSumMetadata.Entry entry) throws IOException, InterruptedException {
        Path dir = NSumPaths.nsumDir().resolve("downloads");
        Files.createDirectories(dir);
        String fileName = entry.downloadLink().substring(
                entry.downloadLink().lastIndexOf('/') + 1);
        if (fileName.isBlank()) {
            fileName = "nsum-runtime-" + entry.version() + ".zip";
        }
        Path target = dir.resolve(fileName);
        LOG.info("[NSUM] 下载 Runtime: {} -> {}", entry.downloadLink(), target);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(entry.downloadLink()))
                .header("User-Agent", "nsum-bridge")
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request,
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("下载失败 HTTP " + response.statusCode());
        }
        if (!entry.sha256().isBlank()) {
            String actual = sha256(target);
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 校验失败（期望 " + entry.sha256() + "，实际 " + actual + "）");
            }
        }
        return target;
    }

    private static Path unpack(Path archive) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".exe")) {
            Path dir = Files.createTempDirectory("nsum-runtime");
            Files.copy(archive, dir.resolve(NSumPaths.RUNTIME_EXE), StandardCopyOption.REPLACE_EXISTING);
            return dir;
        }

        Path root = Files.createTempDirectory("nsum-runtime");
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path destination = root.resolve(entry.getName()).normalize();
                if (!destination.startsWith(root)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    continue;
                }
                Files.createDirectories(destination.getParent());
                try (InputStream in = zip) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        // zip 里可能多一层目录：找含 exe 的那一层
        try (var walk = Files.walk(root, 3)) {
            Path exe = walk.filter(p -> p.getFileName().toString().equals(NSumPaths.RUNTIME_EXE))
                    .findFirst().orElse(null);
            if (exe == null) {
                throw new IOException("压缩包里没有 " + NSumPaths.RUNTIME_EXE);
            }
            return exe.getParent();
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /// bridge 自身更新检查：有新版本则提示（下载到 `.hmcl/nsum/update/`）。
    public static void checkBridgeUpdate() {
        try {
            var project = NSumMetadata.latest(NSumMetadata.TYPE_BRIDGE);
            if (project == null || project.latest().isBlank()) {
                return;
            }
            String current = bridgeVersion();
            if (NSumMetadata.compare(current, project.latest()) >= 0) {
                return;
            }
            var entry = project.latestEntry();
            Platform.runLater(() -> Controllers.confirm(
                    "NSUM Bridge 有新版本：\n当前：" + current + "\n最新："
                            + project.latest() + "\n\n是否下载？（保存到 .hmcl/nsum/update/ 后替换 mods 里的 jar）",
                    "NSUM Bridge 更新", MessageDialogPane.MessageType.QUESTION,
                    () -> downloadBridgeUpdate(entry),
                    () -> LOG.info("[NSUM] 用户跳过 bridge 更新")));
        } catch (Throwable t) {
            LOG.warn("[NSUM] bridge 更新检查失败: {}", t.toString());
        }
    }

    private static void downloadBridgeUpdate(NSumMetadata.Entry entry) {
        if (entry == null || entry.downloadLink().isBlank()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Path dir = NSumPaths.nsumDir().resolve("update");
                Files.createDirectories(dir);
                String fileName = entry.downloadLink().substring(
                        entry.downloadLink().lastIndexOf('/') + 1);
                Path target = dir.resolve(fileName.isBlank() ? "nsum-bridge-" + entry.version() + ".jar" : fileName);
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL).build();
                HttpResponse<Path> response = client.send(
                        HttpRequest.newBuilder(URI.create(entry.downloadLink()))
                                .header("User-Agent", "nsum-bridge").GET().build(),
                        HttpResponse.BodyHandlers.ofFile(target));
                if (response.statusCode() != 200) {
                    throw new IOException("HTTP " + response.statusCode());
                }
                Platform.runLater(() -> Controllers.dialog(
                        "已下载到：\n" + target + "\n\n请退出 HMCL，用该 jar 替换 mods 里的 nsum-bridge-*.jar。",
                        "NSUM Bridge 更新", MessageDialogPane.MessageType.SUCCESS));
            } catch (Exception e) {
                LOG.error("[NSUM] bridge 更新下载失败", e);
                Platform.runLater(() -> Controllers.dialog("下载失败：" + e.getMessage(),
                        "NSUM Bridge 更新", MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// 当前 bridge 版本（Fabric 元数据）。
    public static String bridgeVersion() {
        return FabricLoader.getInstance().getModContainer(NSumBridge.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("0.1.0");
    }
}
