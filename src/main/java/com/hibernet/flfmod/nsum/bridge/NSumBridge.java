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

import com.hibernet.flfmod.api.ModIds;
import com.hibernet.flfmod.api.launch.LaunchStrategies;
import com.hibernet.flfmod.api.modpack.ModpackTypes;
import com.hibernet.flfmod.api.registry.Identifier;
import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// NSUM Bridge 入口：注册整合包类型、启动方式与 GUI。
public final class NSumBridge implements ModInitializer {

    public static final String MOD_ID = "nsum-bridge";

    private static final Logger LOG = LogManager.getLogger("nsum-bridge");

    @Override
    public void onInitialize() {
        LOG.info("[NSUM] NSUM Bridge 初始化中（modId={}）", ModIds.current());

        ModpackTypes.register(Identifier.of(ModIds.current(), NSumModpackProvider.TYPE),
                NSumModpackProvider.INSTANCE);
        LaunchStrategies.register(Identifier.of(ModIds.current(), NSumLaunchStrategy.NAME),
                new NSumLaunchStrategy());

        NSumConfig config = NSumConfig.load();
        NSumRuntime.Status runtime = NSumRuntime.status();
        LOG.info("[NSUM] 注册完成: modpack 类型={} 启动方式={}；runtime={} version={}",
                NSumModpackProvider.TYPE, NSumLaunchStrategy.NAME,
                runtime.installed() ? "已安装" : "未安装", runtime.version());

        if (config.getRuntimeSourceDir().isBlank()) {
            LOG.info("[NSUM] 尚未配置 Runtime 源目录（.hmcl/nsum/config.json → runtimeSourceDir）");
        }

        NSumHttp.installAsDefault();
        NSumUi.register();
        NSumAutoInstaller.checkOnStartup();
        NSumSelfTest.runIfRequested();
    }
}
