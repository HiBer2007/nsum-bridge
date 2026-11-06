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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// NSUM Runtime 的安装、检测与调用。
///
/// <p>Runtime = NSUM 的 CLI 部署副本（`NeoServerUpdateModpack.exe` + Qt/插件/deploy 内容），
/// 安装在 `<HMCL 工作目录>/.hmcl/nsum/runtime/`，由本模组自行复制安装。</p>
public final class NSumRuntime {

    private static final Logger LOG = LogManager.getLogger(NSumRuntime.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /// Runtime 安装状态。
    public record Status(boolean installed, boolean runnable, String version, Path dir, String source) {
        public static Status missing() {
            return new Status(false, false, "", NSumPaths.runtimeDir(), "");
        }
    }

    private NSumRuntime() {
    }

    public static Status status() {
        Path dir = NSumPaths.runtimeDir();
        Path exe = NSumPaths.runtimeExe();
        if (!Files.isDirectory(dir) || !Files.isRegularFile(exe)) {
            return Status.missing();
        }
        String version = "";
        String source = "";
        Path marker = NSumPaths.runtimeMarker();
        if (Files.isRegularFile(marker)) {
            try {
                JsonObject obj = JsonParser.parseString(
                        Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
                if (obj.has("version")) version = obj.get("version").getAsString();
                if (obj.has("source")) source = obj.get("source").getAsString();
            } catch (Exception e) {
                LOG.warn("[NSUM] 读取 runtime 安装标记失败: {}", e.toString());
            }
        }
        return new Status(true, true, version, dir, source);
    }

    /// 从本地部署目录安装（复制到 `.hmcl/nsum/runtime/`）。已存在则覆盖。
    public static Status install(Path sourceDir, String version) throws IOException {
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            throw new IOException("Runtime 源目录不存在: " + sourceDir);
        }
        Path exe = sourceDir.resolve(NSumPaths.RUNTIME_EXE);
        if (!Files.isRegularFile(exe)) {
            throw new IOException("源目录中缺少 " + NSumPaths.RUNTIME_EXE + ": " + sourceDir);
        }

        Path target = NSumPaths.runtimeDir();
        Files.createDirectories(target);
        copyTree(sourceDir, target);

        String effectiveVersion = (version == null || version.isBlank())
                ? "unknown"
                : version;

        JsonObject marker = new JsonObject();
        marker.addProperty("schema", 1);
        marker.addProperty("version", effectiveVersion);
        marker.addProperty("installedAt", Instant.now().toString());
        marker.addProperty("source", sourceDir.toAbsolutePath().normalize().toString());
        Files.writeString(NSumPaths.runtimeMarker(), GSON.toJson(marker), StandardCharsets.UTF_8);

        NSumConfig config = NSumConfig.load();
        config.setLastRuntimeVersion(effectiveVersion);
        config.setRuntimeSourceDir(sourceDir.toAbsolutePath().normalize().toString());
        config.save();

        LOG.info("[NSUM] Runtime 已安装: version={}, dir={}", effectiveVersion, target);
        return status();
    }

    /// HIC 安装器装完后写标记（安装器负责解包，bridge 只记录版本/来源）。
    public static Status markInstalled(String version, String source) throws IOException {
        Files.createDirectories(NSumPaths.runtimeDir());
        JsonObject marker = new JsonObject();
        marker.addProperty("schema", 1);
        marker.addProperty("version", version == null || version.isBlank() ? "unknown" : version);
        marker.addProperty("installedAt", Instant.now().toString());
        marker.addProperty("source", source == null ? "" : source);
        Files.writeString(NSumPaths.runtimeMarker(), GSON.toJson(marker), StandardCharsets.UTF_8);
        NSumConfig config = NSumConfig.load();
        config.setLastRuntimeVersion(version == null ? "" : version);
        config.save();
        return status();
    }

    /// 同步结果。
    public record SyncResult(boolean success, int exitCode, Path logFile, String message) {
    }

    /// 调用 Runtime 同步实例目录。
    public static SyncResult sync(NSumInstance instance, Path versionRoot) {
        Status status = status();
        if (!status.installed()) {
            return new SyncResult(false, -1, null,
                    "NSUM Runtime 未安装（.hmcl/nsum/runtime 缺少可执行文件）");
        }
        if (instance == null || !instance.hasRepo()) {
            return new SyncResult(false, -1, null, "实例未配置仓库地址（.NSUM/instance.json 缺少 repo）");
        }

        List<String> command = new ArrayList<>();
        command.add(NSumPaths.runtimeExe().toString());
        command.add("exec");
        command.add("build");
        command.add("--repo");
        command.add(instance.repo);
        command.add("--modpack");
        command.add(instance.branch == null || instance.branch.isBlank() ? "base" : instance.branch);
        if (instance.gitBranch != null && !instance.gitBranch.isBlank()) {
            command.add("--git-branch");
            command.add(instance.gitBranch);
        }
        command.add("--format");
        command.add("hmcl");
        command.add("--export");
        command.add(versionRoot.toString());

        Path logFile;
        try {
            Files.createDirectories(NSumPaths.logDir());
            logFile = NSumPaths.logDir().resolve("sync-" + System.currentTimeMillis() + ".log");
        } catch (IOException e) {
            return new SyncResult(false, -1, null, "无法创建日志目录: " + e.getMessage());
        }

        LOG.info("[NSUM] 同步开始: {}", String.join(" ", command));
        StringBuilder output = new StringBuilder();
        int exit;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(NSumPaths.hmclHome().toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();
            try (var reader = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = reader.read(buffer)) > 0) {
                    String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
                    output.append(chunk);
                    for (String line : chunk.split("\\R")) {
                        if (!line.isBlank()) {
                            LOG.info("[NSUM] {}", line);
                        }
                    }
                }
            }
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SyncResult(false, -1, logFile, "同步被中断");
        } catch (IOException e) {
            return new SyncResult(false, -1, logFile, "无法启动 Runtime: " + e.getMessage());
        }

        try {
            Files.writeString(logFile, output.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("[NSUM] 写入同步日志失败: {}", e.toString());
        }

        if (exit == 0) {
            instance.touchSyncTime();
            try {
                instance.write(versionRoot);
            } catch (IOException e) {
                LOG.warn("[NSUM] 更新实例同步时间失败: {}", e.toString());
            }
        }

        LOG.info("[NSUM] 同步结束: exit={}, log={}", exit, logFile);
        return new SyncResult(exit == 0, exit, logFile,
                exit == 0 ? "同步成功" : "Runtime 退出码 " + exit);
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else if (Files.isRegularFile(path)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
