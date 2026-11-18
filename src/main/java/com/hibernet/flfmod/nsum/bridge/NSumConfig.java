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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/// 全局配置：`.hmcl/nsum/config.json`。
public final class NSumConfig {

    private static final Logger LOG = LogManager.getLogger(NSumConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// NSUM 部署目录（runtime 的安装来源，本地路径；自动下载留待后续）。
    private String runtimeSourceDir = "";
    /// 是否在启动前自动尝试更新 runtime（当前未实现，仅占位）。
    private boolean autoUpdateRuntime = false;
    /// 上次成功安装的 runtime 版本。
    private String lastRuntimeVersion = "";
    /// 同步失败时是否仍然启动游戏（默认不启动，避免用旧文件进服）。
    private boolean launchOnSyncFailure = false;
    /// 启动时是否自动检查 NSUM Bridge 自身的更新。
    private boolean autoUpdateBridge = true;
    /// 启动前是否弹出「Git 分支 → 整合包分支 → 确认」三步向导。
    private boolean branchWizard = true;
    /// 同步后是否调用 mclhr 的 mod 目录同步（共享库仓 / 多目录聚合）。
    private boolean mclhrSync = true;
    /// 指定 java 可执行文件（空 = JAVA_HOME/bin/java.exe 或 PATH 中的 java）。
    private String javaPath = "";

    public String getRuntimeSourceDir() {
        return runtimeSourceDir == null ? "" : runtimeSourceDir;
    }

    public void setRuntimeSourceDir(String value) {
        this.runtimeSourceDir = value == null ? "" : value;
    }

    public boolean isAutoUpdateRuntime() {
        return autoUpdateRuntime;
    }

    public void setAutoUpdateRuntime(boolean value) {
        this.autoUpdateRuntime = value;
    }

    public String getLastRuntimeVersion() {
        return lastRuntimeVersion == null ? "" : lastRuntimeVersion;
    }

    public void setLastRuntimeVersion(String value) {
        this.lastRuntimeVersion = value == null ? "" : value;
    }

    public boolean isLaunchOnSyncFailure() {
        return launchOnSyncFailure;
    }

    public void setLaunchOnSyncFailure(boolean value) {
        this.launchOnSyncFailure = value;
    }

    public boolean isAutoUpdateBridge() {
        return autoUpdateBridge;
    }

    public void setAutoUpdateBridge(boolean value) {
        this.autoUpdateBridge = value;
    }

    public boolean isBranchWizard() {
        return branchWizard;
    }

    public void setBranchWizard(boolean value) {
        this.branchWizard = value;
    }

    public boolean isMclhrSync() {
        return mclhrSync;
    }

    public void setMclhrSync(boolean value) {
        this.mclhrSync = value;
    }

    public String getJavaPath() {
        return javaPath == null ? "" : javaPath;
    }

    public void setJavaPath(String value) {
        this.javaPath = value == null ? "" : value;
    }

    public static NSumConfig load() {
        Path file = NSumPaths.configFile();
        if (!Files.isRegularFile(file)) {
            return new NSumConfig();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            NSumConfig config = new NSumConfig();
            if (obj.has("runtimeSourceDir")) {
                config.runtimeSourceDir = obj.get("runtimeSourceDir").getAsString();
            }
            if (obj.has("autoUpdateRuntime")) {
                config.autoUpdateRuntime = obj.get("autoUpdateRuntime").getAsBoolean();
            }
            if (obj.has("lastRuntimeVersion")) {
                config.lastRuntimeVersion = obj.get("lastRuntimeVersion").getAsString();
            }
            if (obj.has("launchOnSyncFailure")) {
                config.launchOnSyncFailure = obj.get("launchOnSyncFailure").getAsBoolean();
            }
            if (obj.has("autoUpdateBridge")) {
                config.autoUpdateBridge = obj.get("autoUpdateBridge").getAsBoolean();
            }
            if (obj.has("branchWizard")) {
                config.branchWizard = obj.get("branchWizard").getAsBoolean();
            }
            if (obj.has("mclhrSync")) {
                config.mclhrSync = obj.get("mclhrSync").getAsBoolean();
            }
            if (obj.has("javaPath")) {
                config.javaPath = obj.get("javaPath").getAsString();
            }
            return config;
        } catch (Exception e) {
            LOG.warn("[NSUM] 读取配置失败，使用默认值: {}", e.toString());
            return new NSumConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(NSumPaths.nsumDir());
            Files.writeString(NSumPaths.configFile(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[NSUM] 保存配置失败: {}", e.toString());
        }
    }
}
