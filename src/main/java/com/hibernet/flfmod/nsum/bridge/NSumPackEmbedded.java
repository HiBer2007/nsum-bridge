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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// 安装 `.nsumpack`（pack_format >= 2）的附带文件。
///
/// <p>附带文件由清单 `embedded[]` 声明（导出时按正则选择），内容按 SHA-256
/// 存入 `.hmcl/nsum/cache/files/`，跨整合包/实例复用；清单同时记录来源
/// commit 与 Git 分支，便于追溯。旧包（无 `embedded`）返回 -1 由调用方
/// 回退为解压全部 `overrides/`。</p>
final class NSumPackEmbedded {

    private static final Logger LOG = LogManager.getLogger(NSumPackEmbedded.class);

    private NSumPackEmbedded() {
    }

    static int install(Path zipFile, Path versionRoot, String manifestJson) throws IOException {
        JsonArray embedded = null;
        try {
            JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
            if (manifest.has("embedded") && manifest.get("embedded").isJsonArray()) {
                embedded = manifest.getAsJsonArray("embedded");
            }
        } catch (RuntimeException e) {
            LOG.debug("[NSUM] 解析 embedded 清单失败，按旧格式解压: {}", e.toString());
        }
        if (embedded == null || embedded.isEmpty()) {
            return -1;
        }

        int count = 0;
        try (ZipFile zip = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
            for (JsonElement element : embedded) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String relative = optString(entry, "path", "");
                String sha256 = optString(entry, "sha256", "");
                if (relative.isBlank()) continue;
                Path destination = versionRoot.resolve(relative).normalize();
                if (!destination.startsWith(versionRoot)) {
                    LOG.warn("[NSUM] embedded 路径越界，已跳过: {}", relative);
                    continue;
                }
                Files.createDirectories(destination.getParent());

                Path cacheFile = NSumPaths.fileCacheDir().resolve(sha256);
                if (sha256.length() == 64 && Files.isRegularFile(cacheFile)
                        && sha256.equalsIgnoreCase(sha256(cacheFile))) {
                    Files.copy(cacheFile, destination, StandardCopyOption.REPLACE_EXISTING);
                    count++;
                    continue;
                }

                ZipEntry zipEntry = zip.getEntry("overrides/" + relative);
                if (zipEntry == null || zipEntry.isDirectory()) {
                    LOG.warn("[NSUM] 整合包缺少附带文件: {}", relative);
                    continue;
                }
                try (InputStream in = zip.getInputStream(zipEntry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                if (sha256.length() == 64) {
                    String actual = sha256(destination);
                    if (!sha256.equalsIgnoreCase(actual)) {
                        LOG.warn("[NSUM] 附带文件 SHA-256 不匹配: {}（期望 {}，实际 {}）",
                                relative, sha256, actual);
                    } else {
                        Files.createDirectories(cacheFile.getParent());
                        Files.copy(destination, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                count++;
            }
        }
        return count;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String optString(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
}
