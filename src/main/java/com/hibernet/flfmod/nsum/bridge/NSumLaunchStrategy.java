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

        // HMCL 只认版本目录里的 <id>.json：启动前确保描述存在（幂等）
        NSumVersionDescriptor.ensure(versionRoot.getParent(), versionRoot,
                context.version().getId(), instance.get());

        NSumRuntime.SyncResult result = NSumRuntime.sync(instance.get(), versionRoot);

        if (result.success()) {
            // 同步可能带来新的 .NSUM/version.json，再确认一次描述（已存在则跳过）
            NSumVersionDescriptor.ensure(versionRoot.getParent(), versionRoot,
                    context.version().getId(), instance.get());
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
