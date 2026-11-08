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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/// NSUM 实例在 HMCL 实例列表里的外观：
/// - 默认图标 = bridge 内置的 NSUM 图标（写到 `<版本目录>/icon.png`，HMCL 的
///   `HMCLGameRepository.getVersionIconFile` 会优先读取它）；
/// - 显示名带「（NSUM）」后缀，便于和普通实例区分。
public final class NSumInstanceVisuals {

    private static final Logger LOG = LogManager.getLogger(NSumInstanceVisuals.class);

    public static final String NAME_SUFFIX = "（NSUM）";
    private static final String ICON_RESOURCE = "/nsum-icon.png";

    private NSumInstanceVisuals() {
    }

    /// 给实例目录补上 NSUM 图标（已有任意 icon.* 则跳过）。
    public static void ensureIcon(Path versionRoot) {
        if (versionRoot == null) {
            return;
        }
        try {
            // 其它加载器/整合包可能自带 icon.*，NSUM 实例统一显示 NSUM 图标：
            // 删除非 PNG 旧图标，并始终重写 icon.png。
            try (var stream = Files.list(versionRoot)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (name.startsWith("icon.") && !name.equals("icon.png")) {
                        Files.deleteIfExists(path);
                    }
                }
            } catch (IOException ignored) {
            }
            try (InputStream in = NSumInstanceVisuals.class.getResourceAsStream(ICON_RESOURCE)) {
                if (in == null) {
                    LOG.warn("[NSUM] 缺少内置图标资源 {}", ICON_RESOURCE);
                    return;
                }
                Path target = versionRoot.resolve("icon.png");
                Files.createDirectories(versionRoot);
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[NSUM] 已写入实例图标: {}", target);
            }
        } catch (IOException e) {
            LOG.warn("[NSUM] 写入实例图标失败: {}", e.toString());
        }
    }

    /// 给实例名加 NSUM 标记（已带则不重复）。
    public static String decorateName(String name) {
        if (name == null || name.isBlank()) {
            return NAME_SUFFIX;
        }
        return name.endsWith(NAME_SUFFIX) ? name : name + NAME_SUFFIX;
    }
}
