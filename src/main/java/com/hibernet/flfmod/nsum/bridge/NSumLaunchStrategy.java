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

import com.hibernet.flfmod.api.launch.LaunchContext;
import com.hibernet.flfmod.api.launch.LaunchStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.util.platform.ManagedProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// `nsum-runtime` 启动方式：先让 NSUM Runtime 同步实例，再交回 HMCL 默认启动。
///
/// <p>接管条件 = 实例目录存在 `.NSUM/instance.json`。同步失败时默认中止启动
/// （避免用陈旧文件进服），可在 NSUM 配置里改为仍启动。</p>
public final class NSumLaunchStrategy implements LaunchStrategy {

    public static final String NAME = "nsum-runtime";

    private static final Logger LOG = LogManager.getLogger(NSumLaunchStrategy.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean handles(LaunchContext context) {
        try {
            Path versionRoot = versionRoot(context);
            if (versionRoot == null) {
                return false;
            }
            return Files.isRegularFile(NSumPaths.instanceMarker(versionRoot));
        } catch (Throwable t) {
            LOG.warn("[NSUM] 判断实例是否受管失败: {}", t.toString());
            return false;
        }
    }

    @Override
    public ManagedProcess launch(LaunchContext context) throws Exception {
        Path versionRoot = versionRoot(context);
        Optional<NSumInstance> instance = versionRoot == null
                ? Optional.empty()
                : NSumInstance.read(versionRoot);

        if (instance.isEmpty()) {
            LOG.warn("[NSUM] 实例标记缺失或不可读，回退到 HMCL 默认启动");
            return context.launcher().launch();
        }

        NSumConfig config = NSumConfig.load();
        NSumInstanceVisuals.ensureIcon(versionRoot);

        // 游戏/库/加载器：由 bridge 复用 HMCL 安装器安装；三元组（游戏版本/加载器/加载器版本）
        // 任一变化都会删除旧版本并重装，之后再同步内容。
        if (context.repository() instanceof org.jackhuang.hmcl.game.DefaultGameRepository) {
            var repository = (org.jackhuang.hmcl.game.DefaultGameRepository) context.repository();
            try {
                boolean changed = NSumGameInstaller.ensure(
                        repository, context.version().getId(), instance.get());
                if (changed) {
                    LOG.info("[NSUM] 已按 manifest 安装/切换游戏与加载器，准备重新同步内容");
                }
            } catch (Exception e) {
                String message = "NSUM 安装/切换游戏或加载器失败: " + e.getMessage();
                LOG.error("[NSUM] {}", message, e);
                if (!config.isLaunchOnSyncFailure()) {
                    throw new IllegalStateException(message, e);
                }
                LOG.warn("[NSUM] 配置允许失败后继续启动");
            }
        }

        NSumRuntime.SyncResult result = NSumRuntime.sync(instance.get(), versionRoot);

        if (result.success()) {
            LOG.debug("[NSUM] 同步完成，内容已更新");
        }

        if (!result.success()) {
            String message = "NSUM 同步失败: " + result.message()
                    + (result.logFile() == null ? "" : "（日志: " + result.logFile() + "）");
            LOG.error("[NSUM] {}", message);
            if (!config.isLaunchOnSyncFailure()) {
                throw new IllegalStateException(message);
            }
            LOG.warn("[NSUM] 配置允许同步失败后继续启动，使用现有文件启动游戏");
        }

        // 交回 HMCL 默认启动：FLF API 的 Mixin 用 ThreadLocal 防止重入递归。
        return context.launcher().launch();
    }

    private static Path versionRoot(LaunchContext context) {
        if (context.version() == null || context.repository() == null) {
            return null;
        }
        String id = context.version().getId();
        if (id == null || id.isBlank()) {
            return null;
        }
        return context.repository().getVersionRoot(id);
    }
}
