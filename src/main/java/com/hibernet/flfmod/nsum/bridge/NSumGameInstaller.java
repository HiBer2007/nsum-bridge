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

import com.hibernet.flfmod.api.game.FLFGameInstallSpec;
import com.hibernet.flfmod.api.game.FLFGameRepository;
import com.hibernet.flfmod.api.game.FLFGames;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// 游戏本体 / 依赖库 / 模组加载器的安装与切换。
///
/// <p>NSum 只声明三元组，真正的 HMCL 安装器调用集中在
/// {@link com.hibernet.flfmod.api.game.FLFGameInstaller} 内。</p>
public final class NSumGameInstaller {

    private static final Logger LOG = LogManager.getLogger(NSumGameInstaller.class);

    private NSumGameInstaller() {
    }

    public static String normalizeLoader(String modloader) {
        return FLFGames.installer().normalizeLoader(modloader);
    }

    /// 三元组是否与实例要求一致（且版本目录存在）。
    public static boolean matches(FLFGameRepository repository, String id, NSumInstance instance) {
        if (repository == null || id == null || instance == null) {
            return false;
        }
        Path versionRoot = repository.instanceRoot(id);
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

    /// 安装或切换；完成后把三元组写回实例标记。
    public static boolean ensure(FLFGameRepository repository, String id, NSumInstance instance)
            throws Exception {
        if (matches(repository, id, instance)) {
            return false;
        }
        LOG.info("[NSUM] 安装/切换版本: id={}, mc={}, loader={}, loaderVer={}",
                id, instance.gameVersion, instance.modloader, instance.modloaderVersion);
        boolean changed = FLFGames.installer().ensure(repository, id,
                new FLFGameInstallSpec(instance.gameVersion, instance.modloader,
                        instance.modloaderVersion));
        if (changed) {
            Path versionRoot = repository.instanceRoot(id);
            instance.installedGameVersion = instance.gameVersion;
            instance.installedModloader = instance.modloader;
            instance.installedLoaderVersion = instance.modloaderVersion;
            instance.write(versionRoot);
        }
        return changed;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
