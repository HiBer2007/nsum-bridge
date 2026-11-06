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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/// 用 **HIC 安装器**安装 NSUM Runtime：`--cli --json --silent --path <runtime>`，
/// 读取 HIC 的 JSON 进度事件并回显到 HMCL。
///
/// <p>安装流程随 bridge 提供（`/nsum-install-flow.json`，其中快捷方式步骤
/// `enabled:false`，**不会**创建桌面/开始菜单项）。</p>
public final class NSumHicInstaller {

    private static final Logger LOG = LogManager.getLogger(NSumHicInstaller.class);

    public interface ProgressSink {
        /// percent: 0-100；detail 为步骤/说明
        void progress(int percent, String detail);
    }

    public record Result(boolean success, int exitCode, String message) {
    }

    private NSumHicInstaller() {
    }

    /// 用安装器把 Runtime 装到 `targetDir`。
    public static Result install(Path installerExe, Path targetDir, ProgressSink sink) {
        if (installerExe == null || !Files.isRegularFile(installerExe)) {
            return new Result(false, -1, "安装器不存在: " + installerExe);
        }
        try {
            Path flow = extractFlow();
            List<String> command = new ArrayList<>();
            command.add(installerExe.toString());
            command.add("--cli");
            command.add("--json");
            command.add("--silent");
            command.add("--path");
            command.add(targetDir.toString());
            command.add("--flow");
            command.add(flow.toString());

            LOG.info("[NSUM] 调用 HIC 安装器: {}", String.join(" ", command));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(installerExe.toAbsolutePath().getParent().toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();

            boolean success = false;
            boolean inJson = false;
            StringBuilder block = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("JSON-BEGIN")) {
                        inJson = true;
                        block.setLength(0);
                        continue;
                    }
                    if (line.contains("JSON-END")) {
                        inJson = false;
                        success |= handleEvent(block.toString(), sink);
                        continue;
                    }
                    if (inJson) {
                        block.append(line).append('\n');
                    } else if (!line.isBlank()) {
                        LOG.info("[NSUM] [hic] {}", line);
                    }
                }
            }

            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new Result(false, -1, "安装超时（30 分钟）");
            }
            int exit = process.exitValue();
            LOG.info("[NSUM] HIC 安装器退出码: {}", exit);
            return new Result(exit == 0, exit,
                    exit == 0 ? "安装完成" : "安装器退出码 " + exit);
        } catch (Exception e) {
            LOG.error("[NSUM] HIC 安装失败", e);
            return new Result(false, -1, e.getMessage());
        }
    }

    private static boolean handleEvent(String json, ProgressSink sink) {
        if (json.isBlank()) {
            return false;
        }
        try {
            JsonObject event = JsonParser.parseString(json).getAsJsonObject();
            String type = event.has("event") ? event.get("event").getAsString() : "";
            JsonObject payload = event.has("payload") && event.get("payload").isJsonObject()
                    ? event.getAsJsonObject("payload")
                    : event;
            switch (type) {
                case "progress" -> {
                    int percent = payload.has("percent") ? payload.get("percent").getAsInt() : 0;
                    String step = payload.has("step") ? payload.get("step").getAsString() : "";
                    String detail = payload.has("detail") ? payload.get("detail").getAsString() : "";
                    if (sink != null) {
                        sink.progress(percent, (step.isEmpty() ? "" : step + " ") + detail);
                    }
                }
                case "message" -> {
                    boolean error = payload.has("error") && payload.get("error").getAsBoolean();
                    String text = payload.has("text") ? payload.get("text").getAsString() : "";
                    LOG.log(error ? org.apache.logging.log4j.Level.WARN
                            : org.apache.logging.log4j.Level.INFO, "[NSUM] [hic] {}", text);
                }
                case "finish" -> {
                    boolean ok = payload.has("success") && payload.get("success").getAsBoolean();
                    if (sink != null) {
                        sink.progress(100, ok ? "完成" : "失败");
                    }
                    return ok;
                }
                default -> LOG.debug("[NSUM] HIC 事件: {}", json);
            }
        } catch (Exception e) {
            LOG.debug("[NSUM] 解析 HIC 事件失败: {}", e.toString());
        }
        return false;
    }

    private static Path extractFlow() throws IOException {
        Path dir = NSumPaths.nsumDir().resolve("flow");
        Files.createDirectories(dir);
        Path flow = dir.resolve("nsum-install-flow.json");
        try (InputStream in = NSumHicInstaller.class.getResourceAsStream("/nsum-install-flow.json")) {
            if (in == null) {
                throw new IOException("bridge 内缺少 nsum-install-flow.json 资源");
            }
            Files.copy(in, flow, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return flow;
    }
}
