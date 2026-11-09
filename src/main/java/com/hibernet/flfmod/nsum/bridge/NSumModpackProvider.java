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
import kala.compress.archivers.zip.ZipArchiveReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.modpack.MismatchedModpackTypeException;
import org.jackhuang.hmcl.modpack.Modpack;
import org.jackhuang.hmcl.modpack.ModpackProvider;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.util.io.CompressingUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// `nsum` 整合包类型：把「服务端仓库」封装成一个可导入的 HMCL 整合包。
///
/// <p>包内只有清单 `nsum.modpack.json`（+ 可选 `overrides/`）。安装时创建实例目录、
/// 写入 `.NSUM/instance.json`，内容本身由 NSUM Runtime 在每次启动时同步。</p>
public final class NSumModpackProvider implements ModpackProvider {

    public static final NSumModpackProvider INSTANCE = new NSumModpackProvider();
    public static final String TYPE = "nsum";
    public static final String MANIFEST_NAME = "nsum.index.json";
    public static final String LEGACY_MANIFEST_NAME = "nsum.modpack.json";

    private static final Logger LOG = LogManager.getLogger(NSumModpackProvider.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NSumModpackProvider() {
    }

    @Override
    public String getName() {
        return TYPE;
    }

    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, String version) {
        // 每次启动都会进入该阶段：先完成三元组校验/切换与内容同步，再进入原生启动步骤
        return new NSumSyncTask(dependencyManager, version);
    }

    @Override
    public Task<?> createUpdateTask(DefaultDependencyManager dependencyManager, String name,
                                    Path zipFile, Modpack modpack) throws MismatchedModpackTypeException {
        if (!(modpack instanceof NSumModpack nsum)) {
            throw new MismatchedModpackTypeException(TYPE,
                    modpack.getManifest() == null ? "unknown" : modpack.getManifest().getProvider().getName());
        }
        return nsum.getInstallTask(dependencyManager, zipFile, name, null);
    }

    @Override
    public Modpack readManifest(ZipArchiveReader zipFile, Path file, Charset encoding) throws IOException {
        String json;
        try {
            json = CompressingUtils.readTextZipEntry(zipFile, MANIFEST_NAME);
        } catch (IOException e) {
            try {
                json = CompressingUtils.readTextZipEntry(zipFile, LEGACY_MANIFEST_NAME);
            } catch (IOException ignored) {
                return null; // 不是 nsum 包，交给其它 provider
            }
        }
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();

        NSumModpack modpack = new NSumModpack();
        modpack.setEncoding(encoding);
        modpack.setName(optString(obj, "name", "NSUM"));
        modpack.setAuthor(optString(obj, "author", ""));
        modpack.setVersion(optString(obj, "version", ""));
        modpack.setGameVersion(optString(obj, "gameVersion", ""));
        modpack.setDescription(optString(obj, "description", ""));
        modpack.setManifest(NSumModpackManifest.INSTANCE);
        modpack.setManifestJson(json);

        JsonObject instanceObj = obj.has("instance") && obj.get("instance").isJsonObject()
                ? obj.getAsJsonObject("instance")
                : new JsonObject();
        NSumInstance instance = new NSumInstance();
        instance.name = modpack.getName();
        instance.repo = optString(instanceObj, "repo", "");
        instance.branch = optString(instanceObj, "branch", "base");
        instance.gitBranch = optString(instanceObj, "gitBranch", "");
        instance.branchType = optString(instanceObj, "branchType", "universal");
        instance.gameVersion = modpack.getGameVersion();
        instance.modloader = optString(obj, "modloader", "fabric");
        instance.modloaderVersion = optString(obj, "modloaderVersion", "");
        if (obj.has("runtime") && obj.get("runtime").isJsonObject()) {
            instance.minRuntimeVersion = optString(obj.getAsJsonObject("runtime"), "minVersion", "");
        }
        modpack.setInstance(instance);

        LOG.info("[NSUM] 识别整合包: name={}, version={}, repo={}, branch={}",
                modpack.getName(), modpack.getVersion(), instance.repo, instance.branch);
        return modpack;
    }

    static String optString(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    /// `nsum` 整合包模型。
    public static final class NSumModpack extends Modpack {

        private static final Logger LOG = LogManager.getLogger(NSumModpack.class);

        private NSumInstance instance;
        private String manifestJson = "{}";

        public NSumInstance getInstance() {
            return instance;
        }

        public void setInstance(NSumInstance instance) {
            this.instance = instance;
        }

        public String getManifestJson() {
            return manifestJson;
        }

        public void setManifestJson(String manifestJson) {
            this.manifestJson = manifestJson;
        }

        @Override
        public Task<?> getInstallTask(DefaultDependencyManager dependencyManager, Path zipFile,
                                      String name, String iconUrl) {
            // 实例名带「（NSUM）」后缀，便于在 HMCL 实例列表里区分
            String displayName = NSumInstanceVisuals.decorateName(name);
            return Task.runAsync("NSUM: 安装整合包 " + displayName, () -> {
                Path versionRoot = dependencyManager.getGameRepository().getVersionRoot(displayName);
                Files.createDirectories(versionRoot);

                NSumInstance instance = this.instance != null ? this.instance : new NSumInstance();
                if (instance.name == null || instance.name.isBlank()) {
                    instance.name = displayName;
                }
                instance.write(versionRoot);
                NSumInstanceVisuals.ensureIcon(versionRoot);

                // 游戏本体/库/加载器：复用 HMCL 安装器（会生成 <id>.json），再解 overrides / 同步内容
                if (dependencyManager.getGameRepository()
                        instanceof org.jackhuang.hmcl.game.DefaultGameRepository) {
                    var repository = (org.jackhuang.hmcl.game.DefaultGameRepository)
                            dependencyManager.getGameRepository();
                    try {
                        NSumGameInstaller.ensure(repository, displayName, instance);
                    } catch (Exception e) {
                        LOG.error("[NSUM] 安装游戏/加载器失败: {}", e.toString(), e);
                        throw e;
                    }
                }
                if (!Files.isRegularFile(versionRoot.resolve(displayName + ".json"))) {
                    LOG.warn("[NSUM] 实例缺少版本描述 {}.json（游戏未安装完成？），HMCL 实例列表可能不显示该实例",
                            displayName);
                }

                int extracted = extractOverrides(zipFile, versionRoot);
                LOG.info("[NSUM] 实例已创建: {} (overrides={} 个文件, repo={})",
                        versionRoot, extracted, instance.repo);

                // TODO(installer): 由 NSumGameInstaller 依 manifest 安装原版 + 加载器（含类型切换），
                // 见 PLAN 七点五 §6；当前只建目录/写标记/解 overrides 并同步内容。

                if (NSumRuntime.status().installed()) {
                    NSumRuntime.SyncResult result = NSumRuntime.sync(instance, versionRoot);
                    if (!result.success()) {
                        LOG.warn("[NSUM] 首次同步失败: {}", result.message());
                    }
                } else {
                    LOG.warn("[NSUM] Runtime 未安装，实例只写入了标记；请在 NSUM 页面安装 Runtime 后再启动");
                }
            });
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
                        continue; // 防 zip slip
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
