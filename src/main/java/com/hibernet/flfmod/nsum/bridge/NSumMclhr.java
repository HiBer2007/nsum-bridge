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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// mclhr（multiver class loader handle runtime）兼容层。
///
/// <p>NSUM Runtime 在同步结束时会做两件事（见 `applyMclhrStore`）：</p>
/// <ul>
///   <li>把实例 `mods/` 下的 jar 收进共享内容仓 `&lt;runtime&gt;/stores/mods`（按
///       SHA-256 命名）并用硬链接回指 —— 同一模组在多个分支/实例间只占一份空间；</li>
///   <li>在实例里写 `.mclhr/mods.json`，声明 store 来源与 `link` 模式。</li>
/// </ul>
///
/// <p>这里在每次同步后调用 mclhr CLI 的 `mc mods sync --prune`，让外部
/// mod 目录（如果有）按同一份 catalog 聚合进 `mods/`，并把结果写进
/// `.mclhr/materialized.json`。mclhr 不存在时静默跳过（NSUM 的硬链接仍生效）。</p>
public final class NSumMclhr {

    private static final Logger LOG = LogManager.getLogger(NSumMclhr.class);

    private NSumMclhr() {
    }

    public static void afterSync(Path versionRoot, Consumer<String> onLine) {
        Path jar = NSumPaths.runtimeDir().resolve("tools").resolve("mclhr").resolve("mclhr.jar");
        if (!Files.isRegularFile(jar)) {
            LOG.debug("[mclhr] 未找到 {}，跳过 mclhr 同步（NSUM 硬链接仍然生效）", jar);
            return;
        }
        Path catalog = versionRoot.resolve(".mclhr").resolve("mods.json");
        if (!Files.isRegularFile(catalog)) {
            LOG.debug("[mclhr] 实例没有 .mclhr/mods.json，跳过");
            return;
        }
        String java = javaExecutable();
        List<String> command = new ArrayList<>(List.of(
                java, "-jar", jar.toString(),
                "mc", "mods", "sync",
                "--instance", versionRoot.toString(),
                "--mode", "link",
                "--prune"));
        LOG.info("[mclhr] 同步 mod 目录: {}", String.join(" ", command));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    LOG.info("[mclhr] {}", line);
                    if (onLine != null) {
                        try {
                            onLine.accept("[mclhr] " + line);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.warn("[mclhr] 同步超时，已终止");
                return;
            }
            int exit = process.exitValue();
            if (exit == 0) {
                LOG.info("[mclhr] mod 目录同步完成");
            } else {
                LOG.warn("[mclhr] mod 目录同步失败（exit={}），启动继续", exit);
            }
        } catch (Exception e) {
            LOG.warn("[mclhr] 调用 mclhr 失败: {}", e.toString());
        }
    }

    private static String javaExecutable() {
        NSumConfig config = NSumConfig.load();
        String configured = config.getJavaPath();
        if (!configured.isBlank()) {
            return configured;
        }
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path candidate = Path.of(home, "bin", "java.exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "java";
    }
}
