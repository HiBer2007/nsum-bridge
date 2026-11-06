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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// 生成 HMCL 可识别的实例版本描述 `<实例名>.json`。
///
/// <p>HMCL 只认版本目录里的 `<id>.json`（Minecraft 版本描述）。NSUM 的
/// `.NSUM/version.json` 只是元数据，因此 bridge 自己生成：
/// 优先**继承已安装的 Fabric 版本**（复制其 mainClass/arguments/libraries，
/// 只改 id 与 inheritsFrom），这样依赖链完整、无需重复声明库；
/// 找不到 Fabric 版本时退回“最小描述”（继承原版 + KnotClient + loader 库），
/// 并提示用户先在 HMCL 安装一次 Fabric 以保证依赖完整。</p>
public final class NSumVersionDescriptor {

    private static final Logger LOG = LogManager.getLogger(NSumVersionDescriptor.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NSumVersionDescriptor() {
    }

    /// 生成（幂等）。`versionsRoot` = `.minecraft/versions`，`versionRoot` = 实例目录。
    public static boolean ensure(Path versionsRoot, Path versionRoot, String name, NSumInstance instance) {
        if (versionsRoot == null || versionRoot == null || name == null || name.isBlank()) {
            return false;
        }
        Path target = versionRoot.resolve(name + ".json");
        if (Files.isRegularFile(target)) {
            LOG.debug("[NSUM] 版本描述已存在，跳过: {}", target);
            return true;
        }

        JsonObject base = findFabricBase(versionsRoot, name, instance);
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("id", name);
        descriptor.addProperty("type", "release");
        String now = Instant.now().toString();
        descriptor.addProperty("time", now);
        descriptor.addProperty("releaseTime", now);

        if (base != null) {
            String baseId = base.has("id") ? base.get("id").getAsString() : "";
            descriptor.addProperty("inheritsFrom", baseId);
            for (String key : new String[]{"mainClass", "arguments", "libraries",
                    "minecraftArguments", "jar", "javaVersion", "compatibilityRules"}) {
                if (base.has(key)) {
                    descriptor.add(key, base.get(key));
                }
            }
            LOG.info("[NSUM] 版本描述继承已安装的 Fabric 版本: {}", baseId);
        } else {
            String gameVersion = instance.gameVersion == null || instance.gameVersion.isBlank()
                    ? "1.21.4"
                    : instance.gameVersion;
            String loaderVersion = instance.modloaderVersion == null || instance.modloaderVersion.isBlank()
                    ? "0.16.10"
                    : instance.modloaderVersion;

            descriptor.addProperty("inheritsFrom", gameVersion);
            descriptor.addProperty("mainClass", "net.fabricmc.loader.impl.launch.knot.KnotClient");
            descriptor.add("arguments", minimalArguments());
            descriptor.add("libraries", minimalLibraries(loaderVersion, gameVersion));
            LOG.warn("[NSUM] 未找到已安装的 Fabric 版本，已生成最小版本描述（loader={}, mc={}）；"
                    + "如启动缺库，请先在 HMCL 安装一次 Fabric", loaderVersion, gameVersion);
        }

        try {
            Files.createDirectories(versionRoot);
            Files.writeString(target, GSON.toJson(descriptor), StandardCharsets.UTF_8);
            LOG.info("[NSUM] 已生成版本描述: {}", target);
            return true;
        } catch (IOException e) {
            LOG.error("[NSUM] 写入版本描述失败 {}: {}", target, e.toString());
            return false;
        }
    }

    /// 在 `<versionsRoot>/*/` 中找可继承的 Fabric 版本 JSON（优先 loader/游戏版本都匹配）。
    static JsonObject findFabricBase(Path versionsRoot, String selfName, NSumInstance instance) {
        if (!Files.isDirectory(versionsRoot)) {
            return null;
        }
        List<JsonObject> candidates = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(versionsRoot)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                String dirName = dir.getFileName().toString();
                if (dirName.equals(selfName)) {
                    continue;
                }
                Path json = dir.resolve(dirName + ".json");
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                try {
                    JsonObject obj = JsonParser.parseString(
                            Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (hasFabricLoader(obj)) {
                        candidates.add(obj);
                    }
                } catch (Exception e) {
                    LOG.debug("[NSUM] 跳过无法解析的版本 {}: {}", json, e.toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("[NSUM] 扫描版本目录失败: {}", e.toString());
        }

        if (candidates.isEmpty()) {
            return null;
        }

        // 1) loader 版本匹配；2) 游戏版本匹配；3) 任意
        for (JsonObject obj : candidates) {
            if (loaderMatches(obj, instance.modloaderVersion)
                    && gameVersionMatches(obj, instance.gameVersion)) {
                return obj;
            }
        }
        for (JsonObject obj : candidates) {
            if (loaderMatches(obj, instance.modloaderVersion)) {
                return obj;
            }
        }
        for (JsonObject obj : candidates) {
            if (gameVersionMatches(obj, instance.gameVersion)) {
                return obj;
            }
        }
        return candidates.get(0);
    }

    private static boolean hasFabricLoader(JsonObject obj) {
        return fabricLoaderName(obj) != null;
    }

    private static String fabricLoaderName(JsonObject obj) {
        if (!obj.has("libraries") || !obj.get("libraries").isJsonArray()) {
            return null;
        }
        for (JsonElement element : obj.getAsJsonArray("libraries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject lib = element.getAsJsonObject();
            if (!lib.has("name")) {
                continue;
            }
            String libName = lib.get("name").getAsString();
            if (libName.startsWith("net.fabricmc:fabric-loader:")) {
                return libName;
            }
        }
        return null;
    }

    private static boolean loaderMatches(JsonObject obj, String loaderVersion) {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            return true;
        }
        String libName = fabricLoaderName(obj);
        return libName != null && libName.endsWith(":" + loaderVersion);
    }

    private static boolean gameVersionMatches(JsonObject obj, String gameVersion) {
        if (gameVersion == null || gameVersion.isBlank()) {
            return true;
        }
        if (obj.has("jar") && !obj.get("jar").isJsonNull()
                && gameVersion.equals(obj.get("jar").getAsString())) {
            return true;
        }
        String id = obj.has("id") && !obj.get("id").isJsonNull()
                ? obj.get("id").getAsString()
                : "";
        return id.contains(gameVersion);
    }

    private static JsonObject minimalArguments() {
        JsonObject arguments = new JsonObject();
        arguments.add("game", new JsonArray());
        JsonArray jvm = new JsonArray();
        jvm.add("-DFabricMcEmu=net.minecraft.client.main.Main ");
        arguments.add("jvm", jvm);
        return arguments;
    }

    private static JsonArray minimalLibraries(String loaderVersion, String gameVersion) {
        JsonArray libraries = new JsonArray();
        for (String name : new String[]{
                "net.fabricmc:fabric-loader:" + loaderVersion,
                "net.fabricmc:intermediary:" + gameVersion,
                "net.fabricmc:sponge-mixin:0.15.4+mixin.0.8.7",
                "net.fabricmc:tiny-mappings-parser:0.3.0+build.17",
                "net.fabricmc:tiny-remapper:0.10.4",
                "net.fabricmc:access-widener:2.1.0",
                "org.ow2.asm:asm:9.7.1",
                "org.ow2.asm:asm-analysis:9.7.1",
                "org.ow2.asm:asm-commons:9.7.1",
                "org.ow2.asm:asm-tree:9.7.1",
                "org.ow2.asm:asm-util:9.7.1"
        }) {
            JsonObject lib = new JsonObject();
            lib.addProperty("name", name);
            libraries.add(lib);
        }
        return libraries;
    }
}
