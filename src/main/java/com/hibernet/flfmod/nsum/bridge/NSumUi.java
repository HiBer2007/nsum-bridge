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

import com.hibernet.flfmod.uiapi.FLFComponents;
import com.hibernet.flfmod.uiapi.FLFHMCLUIApi;
import com.hibernet.flfmod.uiapi.FLFHMCLUIApi.SettingsArea;
import com.hibernet.flfmod.uiapi.FLFPage;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/// NSUM 的 GUI：侧栏分类 + 管理页面 + 设置区。
public final class NSumUi {

    private static final Logger LOG = LogManager.getLogger(NSumUi.class);

    private NSumUi() {
    }

    public static void register() {
        FLFHMCLUIApi api = FLFHMCLUIApi.instance();

        api.registerCategory("nsum", "NSUM");
        api.registerPage("nsum-bridge:home", () -> new FLFPage("NSUM", FLFComponents.column(
                bridgeCard(),
                runtimeCard(),
                instancesCard(),
                FLFComponents.card(
                        FLFComponents.label("整合包类型：nsum（导入 *_nsum.zip 即创建受管实例）"),
                        FLFComponents.label("启动方式：nsum-runtime（启动前由 Runtime 同步实例）")))));
        api.registerPageButton("nsum", "home", "NSUM 管理", SVG.SETTINGS, "nsum-bridge:home");

        api.registerSettingsSection(SettingsArea.ABOUT, "NSUM",
                FLFComponents.card(
                        FLFComponents.label("NSUM Runtime 安装在 .hmcl/nsum/runtime，由 NSUM 页面安装。"),
                        FLFComponents.label("启动 NSUM 实例时先同步仓库内容，同步失败默认中止启动。")));
        LOG.info("[NSUM] GUI 注册完成：分类 nsum / 页面 nsum-bridge:home");
    }

    private static Node bridgeCard() {
        java.util.List<Node> children = new java.util.ArrayList<>();
        children.add(FLFComponents.title("NSUM Bridge"));
        children.add(FLFComponents.label("当前版本：" + NSumBridgeUpdater.currentVersion()));
        children.add(FLFComponents.label("更新地址：metadata.hibernet.top（nsum-bridge）"));
        children.add(FLFComponents.button("检查更新", () -> java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                NSumBridgeUpdater.UpdateInfo info = NSumBridgeUpdater.check();
                javafx.application.Platform.runLater(() -> {
                    if (info == null) {
                        org.jackhuang.hmcl.ui.Controllers.dialog(
                                "当前已是最新版本（" + NSumBridgeUpdater.currentVersion() + "）。",
                                "NSUM Bridge",
                                org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO);
                    } else {
                        NSumBridgeUpdater.prompt(info);
                    }
                });
            } catch (Throwable t) {
                javafx.application.Platform.runLater(() -> org.jackhuang.hmcl.ui.Controllers.dialog(
                        "检查更新失败：" + t.getMessage(), "NSUM Bridge",
                        org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR));
            }
        })));
        return FLFComponents.card(children.toArray(Node[]::new));
    }
    private static Node runtimeCard() {
        NSumConfig config = NSumConfig.load();
        NSumRuntime.Status status = NSumRuntime.status();

        List<Node> children = new ArrayList<>();
        children.add(FLFComponents.title("Runtime"));
        children.add(FLFComponents.label(status.installed()
                ? "状态：已安装" + (status.version().isBlank() ? "" : "（" + status.version() + "）")
                : "状态：未安装"));
        children.add(FLFComponents.label("目录：" + NSumPaths.runtimeDir()));
        children.add(FLFComponents.label("分支类型示例：universal（通用）/ client（客户端专用）/ server（服务端专用）"));
        children.add(FLFComponents.label("源目录："
                + (config.getRuntimeSourceDir().isBlank() ? "（未配置）" : config.getRuntimeSourceDir())));

        children.add(FLFComponents.button("安装 / 更新 Runtime", () -> installRuntime(config)));
        children.add(FLFComponents.button("从元数据下载 Runtime", NSumUi::installFromMetadata));
        children.add(FLFComponents.button("从 downloads 安装", NSumAutoInstaller::installFromDownloads));
        return FLFComponents.card(children.toArray(Node[]::new));
    }

    private static void installFromMetadata() {
        CompletableFuture.runAsync(() -> {
            try {
                var project = NSumMetadata.latest(NSumMetadata.TYPE_NSUM);
                if (project == null || project.latest().isBlank()) {
                    Platform.runLater(() -> Controllers.dialog("无法获取 NSUM 元数据，请检查网络或 metadata.hibernet.top",
                            "NSUM", org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR));
                    return;
                }
                Platform.runLater(() -> NSumAutoInstaller.promptInstall(project, project.latest(),
                        NSumRuntime.status().installed()));
            } catch (Throwable t) {
                LOG.warn("[NSUM] 获取元数据失败: {}", t.toString());
            }
        });
    }

    private static void installRuntime(NSumConfig config) {
        List<Path> candidates = new ArrayList<>();
        if (!config.getRuntimeSourceDir().isBlank()) {
            candidates.add(Path.of(config.getRuntimeSourceDir()));
        }
        candidates.add(NSumPaths.hmclHome().resolve("nsum-runtime"));
        candidates.add(NSumPaths.hmclHome().resolve("deploy"));
        candidates.add(NSumPaths.hmclHome().resolve("NeoServerUpdateModpack"));

        Path source = candidates.stream()
                .filter(Files::isDirectory)
                .filter(p -> Files.isRegularFile(p.resolve(NSumPaths.RUNTIME_EXE)))
                .findFirst()
                .orElse(null);

        if (source == null) {
            Controllers.dialog(
                    "找不到 NSUM Runtime 源目录。\n\n"
                            + "请把 NSUM 部署目录放到以下任一位置，或在 .hmcl/nsum/config.json "
                            + "的 runtimeSourceDir 里填写绝对路径：\n"
                            + "  - " + NSumPaths.hmclHome().resolve("nsum-runtime") + "\n"
                            + "  - " + NSumPaths.hmclHome().resolve("deploy"),
                    "NSUM", org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.WARNING);
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                NSumRuntime.Status status = NSumRuntime.install(source, "local");
                Platform.runLater(() -> Controllers.dialog(
                        "Runtime 已安装：" + status.dir() + "\n版本：" + status.version(),
                        "NSUM", org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.SUCCESS));
            } catch (Exception e) {
                LOG.error("[NSUM] 安装 Runtime 失败", e);
                Platform.runLater(() -> Controllers.dialog(
                        "安装失败：" + e.getMessage(),
                        "NSUM", org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static Node instancesCard() {
        List<Path> instances = scanInstances();
        List<Node> children = new ArrayList<>();
        children.add(FLFComponents.title("受管实例（" + instances.size() + "）"));

        if (instances.isEmpty()) {
            children.add(FLFComponents.label("还没有 NSUM 实例。导入 nsum 整合包，或把实例目录加入 NSUM 管理。"));
        } else {
            for (Path root : instances) {
                NSumInstance.read(root).ifPresent(instance -> {
                    children.add(FLFComponents.label(
                            root.getFileName() + "  →  " + instance.repo + " / " + instance.branch
                                    + (instance.lastSync.isBlank() ? "" : "（上次同步 " + instance.lastSync + "）")));
                    children.add(FLFComponents.button(
                            "同步 " + root.getFileName() + " 并生成版本描述",
                            () -> syncOne(root, instance)));
                });
            }
        }

        children.add(FLFComponents.button("同步全部实例", () -> syncAll(instances)));
        return FLFComponents.card(children.toArray(Node[]::new));
    }

    private static void syncOne(Path root, NSumInstance instance) {
        CompletableFuture.runAsync(() -> {
            NSumRuntime.SyncResult result = NSumRuntime.sync(instance, root);
            Platform.runLater(() -> Controllers.dialog(
                    result.success()
                            ? "同步完成：" + root.getFileName()
                            : "同步失败：" + result.message(),
                    "NSUM", result.success()
                            ? org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.SUCCESS
                            : org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.ERROR));
        });
    }

    private static void syncAll(List<Path> instances) {
        CompletableFuture.runAsync(() -> {
            int ok = 0;
            int failed = 0;
            for (Path root : instances) {
                var instance = NSumInstance.read(root);
                if (instance.isEmpty()) {
                    continue;
                }
                NSumRuntime.SyncResult result = NSumRuntime.sync(instance.get(), root);
                if (result.success()) {
                    ok++;
                } else {
                    failed++;
                    LOG.warn("[NSUM] 实例同步失败 {}: {}", root, result.message());
                }
            }
            int successCount = ok;
            int failedCount = failed;
            Platform.runLater(() -> Controllers.dialog(
                    "同步完成：成功 " + successCount + "，失败 " + failedCount + "（详见 .hmcl/nsum/logs）",
                    "NSUM", org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType.INFO));
        });
    }

    private static List<Path> scanInstances() {
        List<Path> result = new ArrayList<>();
        Path versions = NSumPaths.hmclHome().resolve(".minecraft").resolve("versions");
        if (!Files.isDirectory(versions)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(versions)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.isRegularFile(NSumPaths.instanceMarker(p)))
                    .sorted()
                    .forEach(result::add);
        } catch (Exception e) {
            LOG.warn("[NSUM] 扫描实例失败: {}", e.toString());
        }
        return result;
    }
}
