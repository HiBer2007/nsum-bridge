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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hibernet.flfmod.api.game.FLFGameRepository;
import com.hibernet.flfmod.api.game.FLFGames;
import com.hibernet.flfmod.api.modpack.FLFModpack;
import com.hibernet.flfmod.api.modpack.FLFModpackArchive;
import com.hibernet.flfmod.api.modpack.FLFModpackInstallContext;
import com.hibernet.flfmod.api.modpack.FLFModpackManifest;
import com.hibernet.flfmod.api.modpack.FLFModpackProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.task.Task;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// `nsum` 整合包类型：把服务端仓库封装成可导入的整合包。
public final class NSumModpackProvider implements FLFModpackProvider {

    public static final NSumModpackProvider INSTANCE = new NSumModpackProvider();
    public static final String TYPE = "nsum";
    public static final String MANIFEST_NAME = "nsum.index.json";
    public static final String LEGACY_MANIFEST_NAME = "nsum.modpack.json";

    private static final Logger LOG = LogManager.getLogger(NSumModpackProvider.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NSumModpackProvider() {
    }

    @Override
    public String name() {
        return TYPE;
    }

    @Override
    public FLFModpack readManifest(FLFModpackArchive archive, Path file) throws IOException {
        String json;
        try {
            json = archive.readText(MANIFEST_NAME);
        } catch (IOException e) {
            try {
                json = archive.readText(LEGACY_MANIFEST_NAME);
            } catch (IOException ignored) {
                return null;
            }
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        NSumModpack modpack = new NSumModpack();
        modpack.name = optString(obj, "name", "NSUM");
        modpack.author = optString(obj, "author", "");
        modpack.version = optString(obj, "version", "");
        modpack.gameVersion = optString(obj, "gameVersion", "");
        modpack.description = optString(obj, "description", "");
        modpack.manifest = NSumModpackManifest.INSTANCE;
        modpack.manifestJson = json;

        JsonObject instanceObj = obj.has("instance") && obj.get("instance").isJsonObject()
                ? obj.getAsJsonObject("instance")
                : new JsonObject();
        NSumInstance instance = new NSumInstance();
        instance.name = modpack.name;
        instance.repo = optString(instanceObj, "repo", "");
        instance.branch = optString(instanceObj, "branch", "base");
        instance.gitBranch = optString(instanceObj, "gitBranch", "");
        instance.branchType = optString(instanceObj, "branchType", "universal");
        JsonObject repositoryObj = obj.has("repository") && obj.get("repository").isJsonObject()
                ? obj.getAsJsonObject("repository")
                : new JsonObject();
        instance.defaultBranch = optString(repositoryObj, "default_branch", "");
        instance.sourceCommit = optString(repositoryObj, "commit", "");
        instance.sourceGitBranch = optString(repositoryObj, "git_branch", "");
        modpack.packFormat = obj.has("pack_format") ? obj.get("pack_format").getAsInt() : 1;
        modpack.sourceCommit = instance.sourceCommit;
        modpack.sourceGitBranch = instance.sourceGitBranch;
        instance.core = optString(obj, "core", "");
        instance.gameVersion = modpack.gameVersion;
        instance.modloader = optString(obj, "modloader", "fabric");
        instance.modloaderVersion = optString(obj, "modloaderVersion", "");
        if (obj.has("runtime") && obj.get("runtime").isJsonObject()) {
            instance.minRuntimeVersion = optString(obj.getAsJsonObject("runtime"), "minVersion", "");
        }
        modpack.instance = instance;

        LOG.info("[NSUM] 识别整合包: name={}, version={}, repo={}, branch={}",
                modpack.name, modpack.version, instance.repo, instance.branch);
        return modpack;
    }

    @Override
    public Object createCompletionTask(String instanceId, FLFGameRepository repository) {
        return new NSumSyncTask(repository, instanceId);
    }

    @Override
    public Object createUpdateTask(String instanceId, FLFGameRepository repository,
                                   Path archive, FLFModpack modpack, Set<String> excludedFiles) {
        if (modpack instanceof NSumModpack nsum) {
            return Task.runAsync("NSUM: 更新整合包 " + instanceId,
                    () -> nsum.install(new FLFModpackInstallContext(repository, instanceId,
                            repository.instanceRoot(instanceId), archive, excludedFiles)));
        }
        return null;
    }

    static String optString(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    /// `nsum` 整合包模型。
    public static final class NSumModpack implements FLFModpack {

        private static final Logger LOG = LogManager.getLogger(NSumModpack.class);

        private String name = "NSUM";
        private String author = "";
        private String version = "";
        private String gameVersion = "";
        private String description = "";
        private FLFModpackManifest manifest = NSumModpackManifest.INSTANCE;
        private NSumInstance instance;
        private String manifestJson = "{}";
        private int packFormat = 1;
        private String sourceCommit = "";
        private String sourceGitBranch = "";

        @Override
        public String name() {
            return name;
        }

        @Override
        public String author() {
            return author;
        }

        @Override
        public String version() {
            return version;
        }

        @Override
        public String gameVersion() {
            return gameVersion;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public FLFModpackManifest manifest() {
            return manifest;
        }

        public NSumInstance instance() {
            return instance;
        }

        public String manifestJson() {
            return manifestJson;
        }

        @Override
        public void install(FLFModpackInstallContext context) throws Exception {
            Path versionRoot = context.instanceRoot();
            Files.createDirectories(versionRoot);

            NSumInstance instance = this.instance != null ? this.instance : new NSumInstance();
            if (instance.name == null || instance.name.isBlank()) {
                instance.name = NSumInstanceVisuals.decorateName(context.instanceId());
            }
            instance.packFormat = packFormat;
            instance.sourceCommit = sourceCommit;
            instance.sourceGitBranch = sourceGitBranch;
            if (!sourceCommit.isBlank() && instance.gitBranch.isBlank()) {
                instance.gitBranch = sourceGitBranch;
            }
            instance.write(versionRoot);
            NSumInstanceVisuals.ensureIcon(versionRoot);

            NSumGameInstaller.ensure(context.repository(), context.instanceId(), instance);
            int extracted = installEmbedded(context.archive(), versionRoot);
            LOG.info("[NSUM] 实例已创建: {} (pack_format={}, embedded={} 个文件, commit={}, repo={})",
                    versionRoot, packFormat, extracted,
                    sourceCommit.isBlank() ? "-" : sourceCommit.substring(0,
                            Math.min(12, sourceCommit.length())), instance.repo);

            if (NSumRuntime.status().installed()) {
                NSumRuntime.SyncResult result = NSumRuntime.sync(instance, versionRoot);
                if (!result.success()) {
                    LOG.warn("[NSUM] 首次同步失败: {}", result.message());
                }
            } else {
                LOG.warn("[NSUM] Runtime 未安装，实例只写入了标记；请在 NSUM 页面安装 Runtime 后再启动");
            }
        }

        private int installEmbedded(Path zipFile, Path versionRoot) throws IOException {
            int count = NSumPackEmbedded.install(zipFile, versionRoot, manifestJson);
            if (count < 0) {
                return extractOverrides(zipFile, versionRoot);
            }
            return count;
        }

        private int extractOverrides(Path zipFile, Path versionRoot) throws IOException {
            int count = 0;
            try (ZipFile zip = new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (!entryName.startsWith("overrides/") || entry.isDirectory()) {
                        continue;
                    }
                    String relative = entryName.substring("overrides/".length());
                    if (relative.isBlank()) {
                        continue;
                    }
                    Path destination = versionRoot.resolve(relative).normalize();
                    if (!destination.startsWith(versionRoot)) {
                        continue;
                    }
                    Files.createDirectories(destination.getParent());
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
            return count;
        }
    }
}
