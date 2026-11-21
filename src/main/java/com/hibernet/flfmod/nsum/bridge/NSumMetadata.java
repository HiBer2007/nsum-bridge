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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/// 元数据中心（metadata.hibernet.top）客户端。
///
/// <p>仓库 `git@github.com:HiBer2007/metadata.git`；站点通过
/// `https://metadata.hibernet.top/api/metadata?type=<TYPE>` 返回对应 JSON。
/// 登记项目：`NSUM`（Runtime 版本）与 `nsum-bridge`（bridge 本体）。</p>
public final class NSumMetadata {

    private static final Logger LOG = LogManager.getLogger(NSumMetadata.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String DEFAULT_BASE = "https://metadata.hibernet.top";
    public static final String TYPE_NSUM = "NSUM";
    public static final String TYPE_BRIDGE = "nsum-bridge";

    private NSumMetadata() {
    }

    /// 单条版本记录。
    public record Entry(String version, String downloadLink, String releasePage, String sha256) {
    }

    /// 某个项目的 latest + 版本列表。
    public record Project(String project, String latest, java.util.List<Entry> entries) {

        public Entry latestEntry() {
            for (Entry entry : entries) {
                if (entry.version() != null && entry.version().equals(latest)) {
                    return entry;
                }
            }
            return entries.isEmpty() ? null : entries.get(entries.size() - 1);
        }
    }

    /// 拉取（带本地缓存；失败时回退缓存）。
    public static Project latest(String type) throws IOException, InterruptedException {
        Path cache = NSumPaths.nsumDir().resolve("metadata-" + type + ".json");
        String json = null;
        try {
            json = fetch(type);
            Files.createDirectories(cache.getParent());
            Files.writeString(cache, json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("[NSUM] 拉取元数据失败（{}），尝试本地缓存", e.toString());
            if (Files.isRegularFile(cache)) {
                json = Files.readString(cache, StandardCharsets.UTF_8);
            }
        }
        if (json == null) {
            return null;
        }
        return parse(type, json);
    }

    private static String fetch(String type) throws IOException, InterruptedException {
        String base = System.getProperty("nsum.metadata.base", DEFAULT_BASE);
        String url = base + "/api/metadata?type=" + type;
        HttpClient client = NSumHttp.newClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "nsum-bridge")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        LOG.info("[NSUM] 元数据已获取: type={} bytes={}", type, response.body().length());
        return response.body();
    }

    static Project parse(String type, String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        String project = obj.has("project") ? obj.get("project").getAsString() : type;
        String latest = obj.has("Latest") ? obj.get("Latest").getAsString() : "";
        var entries = new java.util.ArrayList<Entry>();
        if (obj.has("List") && obj.get("List").isJsonArray()) {
            JsonArray list = obj.getAsJsonArray("List");
            for (var element : list) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                entries.add(new Entry(
                        string(item, "version"),
                        string(item, "download_link"),
                        string(item, "release_page"),
                        string(item, "sha_256")));
            }
        }
        return new Project(project, latest, entries);
    }

    private static String string(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    /// 简单版本比较：semver 风格（数字段逐位比较），预发布/后缀忽略差异。
    public static int compare(String a, String b) {
        if (a == null) return -1;
        if (b == null) return 1;
        String[] pa = a.toLowerCase(Locale.ROOT).split("[^0-9]+");
        String[] pb = b.toLowerCase(Locale.ROOT).split("[^0-9]+");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            if (pa[i].isEmpty() || pb[i].isEmpty()) {
                continue;
            }
            int va;
            int vb;
            try {
                va = Integer.parseInt(pa[i]);
                vb = Integer.parseInt(pb[i]);
            } catch (NumberFormatException e) {
                int c = pa[i].compareTo(pb[i]);
                if (c != 0) return Integer.signum(c);
                continue;
            }
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    /// 写出示例 JSON（自检用）。
    static String toJson(Project project) {
        return GSON.toJson(project);
    }
}
