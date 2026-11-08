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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.task.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// 启动流程中的 NSUM 任务：HMCL 的「处理游戏依赖」阶段会调用
/// `ModpackProvider#createCompletionTask`，本任务在该阶段完成：
///
/// 1. 检查实例三元组（游戏版本 / 加载器 / 加载器版本），不一致则安装或切换；
/// 2. 调用 NSUM Runtime 同步整合包内容（指针下载、覆盖规则、serverconfig）。
///
/// <p>阶段名直接写入 HMCL 启动进度对话框的任务行（`setStage`），
/// 从而在**原生启动步骤开始之前**展示「正在做什么」。</p>
public final class NSumSyncTask extends Task<Void> {

    private static final Logger LOG = LogManager.getLogger(NSumSyncTask.class);

    private static final Pattern STEP = Pattern.compile("\\[([A-Za-z_]+)\\s+(\\d+)%]\\s*(.*)");
    private static final Pattern BAR = Pattern.compile("(\\d+)%\\s*\\|\\s*([^\\[]*)");

    private final DefaultDependencyManager dependencyManager;
    private final String versionId;

    public NSumSyncTask(DefaultDependencyManager dependencyManager, String versionId) {
        this.dependencyManager = dependencyManager;
        this.versionId = versionId;
        setName("NSUM 更新检查");
        setStage("NSUM 更新检查");
    }

    @Override
    public void execute() throws Exception {
        DefaultGameRepository repository = dependencyManager.getGameRepository();
        Path versionRoot = repository.getVersionRoot(versionId);
        Optional<NSumInstance> read = NSumInstance.read(versionRoot);
        if (read.isEmpty()) {
            LOG.info("[NSUM] 实例标记缺失，跳过同步: {}", versionRoot);
            setStage("NSUM：跳过（非受管实例）");
            return;
        }
        NSumInstance instance = read.get();
        NSumConfig config = NSumConfig.load();
        NSumLaunchDialog ui = NSumLaunchDialog.mount();
        ui.setStatus("检查实例与同步状态…");
        NSumInstanceVisuals.ensureIcon(versionRoot);

        setStage("NSUM：检查游戏与加载器版本");
        ui.setStatus("检查游戏与加载器版本…");
        ui.setDetail(instance.gameVersion + " · " + instance.modloader + " " + instance.modloaderVersion);
        try {
            boolean changed = NSumGameInstaller.ensure(repository, versionId, instance);
            if (changed) {
                setStage("NSUM：已安装/切换游戏与加载器");
                ui.setStatus("已安装/切换游戏与加载器");
            }
        } catch (Exception e) {
            LOG.error("[NSUM] 安装/切换游戏或加载器失败", e);
            if (!config.isLaunchOnSyncFailure()) {
                throw new IOException("NSUM 安装/切换游戏或加载器失败: " + e.getMessage(), e);
            }
        }

        setStage("NSUM：同步整合包内容…");
        ui.setStatus("同步整合包内容…");
        NSumRuntime.SyncResult result = NSumRuntime.sync(instance, versionRoot, line -> {
            onLine(line);
            ui.acceptLine(line);
        }, this::isCancelled);
        if (isCancelled()) {
            ui.setStatus("已取消同步");
            throw new InterruptedException("NSUM 同步已取消");
        }
        if (!result.success()) {
            LOG.error("[NSUM] 同步失败: {}", result.message());
            ui.setStatus("同步失败：" + result.message());
            if (!config.isLaunchOnSyncFailure()) {
                throw new IOException("NSUM 同步失败: " + result.message());
            }
        }
        ui.finish("同步完成，准备启动游戏…");
        setStage("NSUM：同步完成");
    }

    private void onLine(String line) {
        long slowMs = Long.getLong("nsum.debug.slowSyncMs", 0L);
        if (slowMs > 0) {
            try {
                Thread.sleep(slowMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (line == null || line.isBlank()) {
            return;
        }
        String text = line.replace("\u001b", "");
        Matcher step = STEP.matcher(text);
        if (step.find()) {
            setStage("NSUM：" + describe(step.group(1)) + " " + step.group(2) + "%");
            return;
        }
        Matcher bar = BAR.matcher(text);
        if (bar.find()) {
            String detail = bar.group(2).trim();
            if (!detail.isEmpty()) {
                setStage("NSUM：" + detail + " " + bar.group(1) + "%");
            }
            return;
        }
        if (text.startsWith("[*] ") || text.startsWith("[+] ")) {
            setStage("NSUM：" + text.substring(4).trim());
        }
    }

    private static String describe(String stage) {
        return switch (stage) {
            case "clone" -> "拉取仓库";
            case "checkout" -> "切换分支";
            case "merge" -> "合并分支";
            case "process_files" -> "处理文件";
            case "pointer" -> "下载指针文件";
            case "config" -> "同步配置";
            case "custom_mods" -> "处理自定义模组";
            case "finalize" -> "生成版本文件";
            case "server_config" -> "同步存档配置";
            case "download" -> "下载";
            default -> stage;
        };
    }
}
