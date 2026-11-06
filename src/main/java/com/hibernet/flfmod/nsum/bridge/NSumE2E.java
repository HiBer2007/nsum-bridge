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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// 端到端自检：把导出的 `.nsumpack` 当作 HMCL 导入流程处理
/// （建实例目录 + 写标记 + 图标 + 解 overrides），再跑一次真实 Runtime 同步，
/// 核对 L1/L2/L3 规则、指针下载、保护文件等结果。
///
/// 由 `-Dnsum.selftest.e2e.zip=<zip>` 与 `-Dnsum.selftest.e2e.target=<预置目标模板>` 触发。
final class NSumE2E {

    private static final Logger LOG = LogManager.getLogger(NSumE2E.class);

    private NSumE2E() {
    }

    static boolean run(Path zip, Path targetTemplate) {
        int passed = 0;
        int failed = 0;
        try {
            Path root = Files.createTempDirectory("nsum-e2e");
            JsonObject manifest = readManifest(zip);
            if (manifest == null) {
                LOG.error("[NSUM-E2E] 压缩包里没有 nsum.index.json");
                return false;
            }

            String id = NSumInstanceVisuals.decorateName(manifest.get("name").getAsString());
            var repository = new org.jackhuang.hmcl.game.DefaultGameRepository(root);
            Path versionRoot = repository.getVersionRoot(id);
            Files.createDirectories(versionRoot);
            Files.writeString(versionRoot.resolve(id + ".json"),
                    "{\"id\":\"" + id + "\"}", StandardCharsets.UTF_8);

            NSumInstance instance = new NSumInstance();
            instance.name = id;
            instance.repo = manifest.getAsJsonObject("instance").get("repo").getAsString();
            instance.branch = manifest.getAsJsonObject("instance").get("branch").getAsString();
            instance.gitBranch = manifest.getAsJsonObject("instance").get("gitBranch").getAsString();
            instance.gameVersion = manifest.get("gameVersion").getAsString();
            instance.modloader = manifest.get("modloader").getAsString();
            instance.modloaderVersion = manifest.get("modloaderVersion").getAsString();
            instance.installedGameVersion = instance.gameVersion;
            instance.installedModloader = instance.modloader;
            instance.installedLoaderVersion = instance.modloaderVersion;
            instance.write(versionRoot);
            NSumInstanceVisuals.ensureIcon(versionRoot);

            extractOverrides(zip, versionRoot);
            // 模拟「已安装实例的再次同步」：先放覆盖层，再放入本地既有内容
            if (targetTemplate != null && Files.isDirectory(targetTemplate)) {
                copyTree(targetTemplate, versionRoot);
            }

            var result = NSumRuntime.sync(instance, versionRoot);
            passed += check("实例名带（NSUM）", id.endsWith(NSumInstanceVisuals.NAME_SUFFIX), id);
            passed += check("实例图标", Files.isRegularFile(versionRoot.resolve("icon.png")), "");
            passed += check("instance.json", Files.isRegularFile(NSumPaths.instanceMarker(versionRoot)), "");
            passed += check("版本描述 <id>.json", Files.isRegularFile(versionRoot.resolve(id + ".json")), "");
            passed += check("Runtime 同步 exit=0", result.success(), result.message());

            passed += check("L1 mirror 覆盖", hasText(versionRoot.resolve("config/full.json"), "repo"), "");
            passed += check("L1 mirror 删多余", !Files.exists(versionRoot.resolve("config/deleted.json")), "");
            passed += check("L1 incremental_add 保留", Files.isRegularFile(versionRoot.resolve("resourcepacks/old.zip")), "");
            passed += check("L1 skip 不动", hasText(versionRoot.resolve("logs/should-skip.log"), "TARGET-LOG"), "");
            passed += check("L1 default 兜底删除多余", !Files.exists(versionRoot.resolve("testdefault/extra.txt")), "");
            passed += check("L2 partial 保留本地键", hasText(versionRoot.resolve("config/partial.json"), "TARGET-LOCAL"), "");
            passed += check("B 指针已下载", Files.isRegularFile(versionRoot.resolve("mods/pointer-mod.jar")), "");
            passed += check("E1 L3 full 覆盖", hasText(versionRoot.resolve("saves/World1/serverconfig/gamesettings.json"), "hard"), "");
            passed += check("E1 L3 partial 保留 custom", hasText(versionRoot.resolve("saves/World1/serverconfig/gamesettings.json"), "keep-me"), "");
            passed += check("E3 L3 ignore 保持目标", hasText(versionRoot.resolve("saves/World1/serverconfig/rules.json"), "fromTarget"), "");
            passed += check("E2 存档自动创建", Files.isRegularFile(versionRoot.resolve("saves/World2/serverconfig/worldflags.json")), "");
            passed += check("F2b custom 模组收获", Files.isRegularFile(versionRoot.resolve(".NSUM/custom/mod/custom-local.jar")), "");
            passed += check("F3 modpack.cfg 保护", hasText(versionRoot.resolve("modpack.cfg"), "custom=1"), "");

            LOG.info("[NSUM-E2E] 结果: passed={} failed={}", passed, failed);
            return failed == 0;
        } catch (Throwable t) {
            LOG.error("[NSUM-E2E] 异常", t);
            return false;
        }
    }

    private static int check(String name, boolean ok, String detail) {
        LOG.info("[NSUM-E2E] {}{} {}", name, ok ? " PASS" : " FAIL", detail == null ? "" : detail);
        return ok ? 1 : 0;
    }

    private static boolean hasText(Path file, String text) {
        try {
            return Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).contains(text);
        } catch (IOException e) {
            return false;
        }
    }

    private static JsonObject readManifest(Path zip) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.getEntry("nsum.index.json");
            if (entry == null) {
                return null;
            }
            try (InputStream in = zf.getInputStream(entry)) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        }
    }

    private static void extractOverrides(Path zip, Path target) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("overrides/") || entry.isDirectory()) {
                    continue;
                }
                Path dest = target.resolve(name.substring("overrides/".length())).normalize();
                if (!dest.startsWith(target)) {
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zf.getInputStream(entry)) {
                    Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
