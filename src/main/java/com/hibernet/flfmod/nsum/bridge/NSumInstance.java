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
import java.time.Instant;
import java.util.Optional;

/// 实例级 NSUM 标记：<实例目录>/.NSUM/instance.json。
///
/// <p>存在该文件即代表「该实例由 NSUM 管理」：启动时走
/// nsum-runtime 策略，
/// 先让 Runtime 同步仓库内容再交给 HMCL 启动。</p>
public final class NSumInstance {

    private static final Logger LOG = LogManager.getLogger(NSumInstance.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public int schema = 1;
    /// 仓库地址：http(s) URL 或本地路径。
    public String repo = "";
    /// 整合包分支（合并用）。
    public String branch = "base";
    /// Git 分支（checkout 用；空 = 与整合包分支同名）。
    public String gitBranch = "";
    /// 展示名。
    public String name = "NSUM";
    /// 游戏版本（如 1.21.4），生成版本描述用。
    public String gameVersion = "";
    /// 加载器（fabric）。
    public String modloader = "";
    /// 加载器版本（如 0.16.10），生成版本描述用。
    public String modloaderVersion = "";
    /// Runtime 最低版本要求（空 = 不限制）。
    public String minRuntimeVersion = "";
    /// 上次同步时间（ISO-8601，UTC）。
    public String lastSync = "";

    public static Optional<NSumInstance> read(Path versionRoot) {
        Path file = NSumPaths.instanceMarker(versionRoot);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            NSumInstance instance = new NSumInstance();
            if (obj.has("schema")) instance.schema = obj.get("schema").getAsInt();
            if (obj.has("repo")) instance.repo = obj.get("repo").getAsString();
            if (obj.has("branch")) instance.branch = obj.get("branch").getAsString();
            if (obj.has("gitBranch")) instance.gitBranch = obj.get("gitBranch").getAsString();
            if (obj.has("name")) instance.name = obj.get("name").getAsString();
            if (obj.has("gameVersion")) instance.gameVersion = obj.get("gameVersion").getAsString();
            if (obj.has("modloader")) instance.modloader = obj.get("modloader").getAsString();
            if (obj.has("modloaderVersion")) instance.modloaderVersion = obj.get("modloaderVersion").getAsString();
            if (obj.has("minRuntimeVersion")) {
                instance.minRuntimeVersion = obj.get("minRuntimeVersion").getAsString();
            }
            if (obj.has("lastSync")) instance.lastSync = obj.get("lastSync").getAsString();
            return Optional.of(instance);
        } catch (Exception e) {
            LOG.warn("[NSUM] 解析实例标记失败 {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public void write(Path versionRoot) throws IOException {
        Path file = NSumPaths.instanceMarker(versionRoot);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    public void touchSyncTime() {
        lastSync = Instant.now().toString();
    }

    public boolean hasRepo() {
        return repo != null && !repo.isBlank();
    }

    public boolean matchesType(String type) {
        return type != null && type.equals(NSumModpackProvider.TYPE);
    }
}
