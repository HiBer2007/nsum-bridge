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

import java.nio.file.Files;
import java.nio.file.Path;

/// 从 Runtime 目录启动 NSUM 编辑器（`NeoWorkspaceEditor.exe`）。
///
/// <p>用户裁定：Runtime 必须包含编辑器；点击实例的「编辑」即打开该实例对应仓库。
/// 编辑器接受一个位置参数（仓库目录或 workspace.json 路径）。</p>
public final class NSumEditor {

    private static final Logger LOG = LogManager.getLogger(NSumEditor.class);

    private NSumEditor() {
    }

    public static Path editorExe() {
        return NSumPaths.runtimeDir().resolve("NeoWorkspaceEditor.exe");
    }

    public static boolean available() {
        return Files.isRegularFile(editorExe());
    }

    /// 打开编辑器并加载仓库；返回是否成功拉起进程。
    public static boolean open(String repo) {
        Path exe = editorExe();
        if (!Files.isRegularFile(exe)) {
            LOG.warn("[NSUM] Runtime 中缺少编辑器: {}", exe);
            return false;
        }
        if (repo == null || repo.isBlank()) {
            LOG.warn("[NSUM] 实例未配置仓库地址，无法打开编辑器");
            return false;
        }
        try {
            new ProcessBuilder(exe.toString(), repo)
                    .directory(NSumPaths.runtimeDir().toFile())
                    .redirectErrorStream(true)
                    .start();
            LOG.info("[NSUM] 已启动编辑器: {} {}", exe, repo);
            return true;
        } catch (Exception e) {
            LOG.warn("[NSUM] 启动编辑器失败: {}", e.toString());
            return false;
        }
    }
}
