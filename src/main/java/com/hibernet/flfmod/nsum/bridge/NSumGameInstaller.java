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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.download.GameBuilder;
import org.jackhuang.hmcl.game.DefaultGameRepository;
import org.jackhuang.hmcl.setting.DownloadProviders;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/// 游戏本体 / 依赖库 / 模组加载器的安装与切换。
///
/// <p>**分工**：NSUM 只提供整合包内容（mods/config/saves 等）；游戏与加载器由
/// bridge 复用 **HMCL 自己的安装器**（`DefaultDependencyManager` + `GameBuilder`）
/// 安装——产物是 HMCL 标准版本目录与 `<id>.json`，bridge 不再手写版本描述。</p>
///
/// <p>**自动切换**：`instance.json` 记录 `installedGameVersion/installedModloader/
/// installedModloaderVersion`；导入时与启动前比对，任一变化（含加载器类型切换）
/// 都会删除旧版本目录并按新三元组重装，然后由调用方重新同步内容。</p>
public final class NSumGameInstaller {

    private static final Logger LOG = LogManager.getLogger(NSumGameInstaller.class);

    private NSumGameInstaller() {
    }

    /// 加载器别名归一（fabric / quilt / forge / neoforge / optifine / liteloader）。
    public static String normalizeLoader(String modloader) {
        if (modloader == null) {
            return "";
        }
        String value = modloader.toLowerCase(Locale.ROOT).trim();
        return switch (value) {
            case "fabric", "fabric-loader", "fabricloader" -> "fabric";
            case "quilt", "quilt-loader" -> "quilt";
            case "forge", "forge-loader" -> "forge";
            case "neoforge", "neo-forge" -> "neoforge";
            case "optifine" -> "optifine";
            case "liteloader" -> "liteloader";
            default -> value;
        };
    }

    /// 三元组是否与实例要求一致（且版本目录存在）。空值视为“未知，不做判断”。
    public static boolean matches(DefaultGameRepository repository, String id, NSumInstance instance) {
        if (repository == null || id == null || instance == null) {
            return false;
        }
        Path versionRoot = repository.getVersionRoot(id);
        if (!Files.isRegularFile(versionRoot.resolve(id + ".json"))) {
            return false;
        }
        if (!blank(instance.installedGameVersion)
                && !Objects.equals(instance.installedGameVersion, instance.gameVersion)) {
            return false;
        }
        if (!blank(instance.installedModloader)
                && !Objects.equals(normalizeLoader(instance.installedModloader),
                        normalizeLoader(instance.modloader))) {
            return false;
        }
        return blank(instance.installedLoaderVersion)
                || Objects.equals(instance.installedLoaderVersion, instance.modloaderVersion);
    }

    /// 安装或切换（阻塞直到完成；内部用 HMCL 的 Task 链）。
    ///
    /// @return true = 已安装/已切换；false = 无需变更
    public static boolean ensure(DefaultGameRepository repository, String id, NSumInstance instance)
            throws Exception {
        if (matches(repository, id, instance)) {
            return false;
        }
        LOG.info("[NSUM] 安装/切换版本: id={}, mc={}, loader={}, loaderVer={}（原记录 {}/{}/{}）",
                id, instance.gameVersion, instance.modloader, instance.modloaderVersion,
                instance.installedGameVersion, instance.installedModloader,
                instance.installedLoaderVersion);

        Path versionRoot = repository.getVersionRoot(id);
        Path backupRoot = null;
        if (Files.isRegularFile(versionRoot.resolve(id + ".json"))) {
            // 已存在但三元组不同：先把旧版本目录挪到旁边（而不是直接删除），
            // 安装/下载失败时再还原，避免「下载失败 → 实例被清空」。
            backupRoot = versionRoot.resolveSibling(id + ".nsum-backup");
            deleteDirectory(backupRoot);
            LOG.info("[NSUM] 备份旧版本目录以切换: {} -> {}", versionRoot, backupRoot);
            Files.move(versionRoot, backupRoot);
        }

        String loader = normalizeLoader(instance.modloader);
        if (instance.gameVersion == null || instance.gameVersion.isBlank()) {
            throw new IOException("nsum.modpack.json 缺少 gameVersion，无法安装游戏");
        }

        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                DownloadProviders.getDownloadProvider(),
                new DefaultCacheRepository());

        GameBuilder builder = dependencyManager.gameBuilder()
                .name(id)
                .gameVersion(instance.gameVersion);
        if (!loader.isBlank() && instance.modloaderVersion != null
                && !instance.modloaderVersion.isBlank()) {
            builder.version(loader, instance.modloaderVersion);
        }

        Task<?> task = builder.buildAsync();
        try {
            runBlocking(task, 30);
        } catch (Exception e) {
            // 安装失败：清掉半成品，还原旧版本，保证实例仍可用
            if (backupRoot != null) {
                try {
                    deleteDirectory(versionRoot);
                    Files.move(backupRoot, versionRoot);
                    LOG.warn("[NSUM] 安装失败，已还原旧版本目录: {}", versionRoot);
                } catch (IOException restoreError) {
                    LOG.error("[NSUM] 还原旧版本目录失败: {}", restoreError.toString());
                }
            }
            throw e;
        }
        if (backupRoot != null) {
            deleteDirectory(backupRoot);
        }
        instance.installedGameVersion = instance.gameVersion;
        instance.installedModloader = loader;
        instance.installedLoaderVersion = instance.modloaderVersion;
        instance.write(versionRoot);
        LOG.info("[NSUM] 版本安装完成: {}", versionRoot);
        return true;
    }

    /// 同步执行 HMCL Task（安装链），超时即中止。
    private static void runBlocking(Task<?> task, int timeoutMinutes) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        TaskExecutor executor = task.executor(new TaskListener() {
            @Override
            public void onFailed(Task<?> failedTask, Throwable throwable) {
                failure.set(throwable);
                LOG.warn("[NSUM] 安装任务失败: {}", throwable.toString());
            }

            @Override
            public void onStop(boolean success, TaskExecutor taskExecutor) {
                if (!success && failure.get() == null) {
                    failure.set(new IllegalStateException("安装任务未成功完成"));
                }
                latch.countDown();
            }
        });
        executor.start();

        if (!latch.await(timeoutMinutes, TimeUnit.MINUTES)) {
            executor.cancel();
            throw new IOException("安装超时（" + timeoutMinutes + " 分钟）");
        }
        if (failure.get() != null) {
            throw new IOException("安装失败: " + failure.get().getMessage(), failure.get());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
