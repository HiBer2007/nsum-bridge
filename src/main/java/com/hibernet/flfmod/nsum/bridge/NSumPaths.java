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

import java.nio.file.Path;
import java.nio.file.Paths;

/// NSUM 相关路径集中定义。
///
/// <p>HMCL 的元数据根是进程工作目录下的 `.hmcl`（与 HMCL 自身、sso-bridge 一致）。
/// NSUM 自己的东西全部收在 `.hmcl/nsum/` 下，不污染 HMCL 的目录结构。</p>
public final class NSumPaths {

    public static final String RUNTIME_EXE = "NeoServerUpdateModpack.exe";
    public static final String RUNTIME_MARKER = ".nsum-runtime.json";
    public static final String INSTANCE_MARKER = "instance.json";

    private NSumPaths() {
    }

    /// HMCL 工作目录（进程 CWD）。
    public static Path hmclHome() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    /// `<HMCL 工作目录>/.hmcl`。
    public static Path metadataRoot() {
        return hmclHome().resolve(".hmcl");
    }

    /// `.hmcl/nsum/`。
    public static Path nsumDir() {
        return metadataRoot().resolve("nsum");
    }

    /// `.hmcl/nsum/config.json`。
    public static Path configFile() {
        return nsumDir().resolve("config.json");
    }

    /// `.hmcl/nsum/runtime/`。
    public static Path runtimeDir() {
        return nsumDir().resolve("runtime");
    }

    /// `.hmcl/nsum/runtime/.nsum-runtime.json`。
    public static Path runtimeMarker() {
        return runtimeDir().resolve(RUNTIME_MARKER);
    }

    /// `.hmcl/nsum/runtime/NeoServerUpdateModpack.exe`。
    public static Path runtimeExe() {
        return runtimeDir().resolve(RUNTIME_EXE);
    }

    /// `.hmcl/nsum/logs/`。
    public static Path logDir() {
        return nsumDir().resolve("logs");
    }

    /// 实例标记：`<实例目录>/.NSUM/instance.json`。
    public static Path instanceMarker(Path versionRoot) {
        return versionRoot.resolve(".NSUM").resolve(INSTANCE_MARKER);
    }
}
