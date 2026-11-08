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

import com.hibernet.flfmod.api.launch.LaunchContext;
import com.hibernet.flfmod.api.launch.LaunchStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.util.platform.ManagedProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// `nsum-runtime` 启动方式：接管受管实例的启动，交回 HMCL 默认启动。
///
/// <p>接管条件 = 实例目录存在 `.NSUM/instance.json`。</p>
///
/// <p>**同步时机**：正常实例会在 HMCL 的「处理游戏依赖」阶段由
/// `NSumModpackProvider#createCompletionTask`（`NSumSyncTask`）完成版本校验与
/// 内容同步，本策略只负责交回启动。仅当实例缺少 `modpack.json`（HMCL 不会调用
/// completion task 的旧实例/手工放置场景）时，这里做一次兜底同步。</p>
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

        // 正常路径：completion task 已完成同步；这里只兜底没有 modpack.json 的实例
        boolean completionTaskRuns = Files.isRegularFile(versionRoot.resolve("modpack.json"));
        if (!completionTaskRuns
                && context.repository() instanceof DefaultGameRepository repository) {
            LOG.info("[NSUM] 实例缺少 modpack.json，启动策略内执行兜底同步");
            try {
                NSumGameInstaller.ensure(repository, context.version().getId(), instance.get());
                NSumRuntime.SyncResult result = NSumRuntime.sync(
                        instance.get(), versionRoot, null,
                        () -> Thread.currentThread().isInterrupted() || config.isLaunchOnSyncFailure() && false);
                if (!result.success()) {
                    String message = "NSUM 同步失败: " + result.message();
                    LOG.error("[NSUM] {}", message);
                    if (!config.isLaunchOnSyncFailure()) {
                        throw new IllegalStateException(message);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                String message = "NSUM 安装/同步失败: " + e.getMessage();
                LOG.error("[NSUM] {}", message, e);
                if (!config.isLaunchOnSyncFailure()) {
                    throw new IllegalStateException(message, e);
                }
            }
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
