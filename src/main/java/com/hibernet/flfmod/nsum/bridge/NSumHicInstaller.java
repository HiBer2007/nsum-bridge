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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// 用 **HIC CLI 壳**（`hci_cli.exe`）无人值守安装 NSUM Runtime。
///
/// <p>CLI 分发目录由 NSUM 构建产出（`nsum_cli_dist/`：`hci_cli.exe` + `product.json`
/// + `install.json` + `LICENSE.txt` + `deploy/`）。`install.json` 内快捷方式步骤
/// `enabled:false`，**不会创建桌面/开始菜单项**。</p>
public final class NSumHicInstaller {

    private static final Logger LOG = LogManager.getLogger(NSumHicInstaller.class);

    public interface ProgressSink {
        void progress(int percent, String detail);
    }

    public record Result(boolean success, int exitCode, String message) {
    }

    public record ExternalDownload(String name, String url, long size, String step) {
    }

    public record ExternalCheck(int exitCode, List<ExternalDownload> downloads, String manifest) {
    }

    private NSumHicInstaller() {
    }

    /// 新版 HIC CLI 才支持外部下载模式。
    public static boolean supportsExternal(Path distDir) {
        Path exe = distDir == null ? null : distDir.resolve("hci_cli.exe");
        if (exe == null || !Files.isRegularFile(exe)) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(exe.toString(), "--help")
                    .directory(distDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("--external-download")) {
                        process.destroy();
                        return true;
                    }
                }
            }
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 旧版没有该参数
        }
        return false;
    }

    /// 运行一次外部下载模式；exit 10 表示需要把清单里的文件放到 files/ 后重跑。
    public static ExternalCheck checkExternal(Path distDir, Path targetDir, Path externalDir,
                                              ProgressSink sink, BooleanSupplier cancelled) {
        Path exe = distDir.resolve("hci_cli.exe");
        Path product = distDir.resolve("product.json");
        Path flow = distDir.resolve("install.json");
        if (!Files.isRegularFile(exe) || !Files.isRegularFile(product)
                || !Files.isRegularFile(flow)) {
            return new ExternalCheck(-1, List.of(), null);
        }
        List<String> args = new ArrayList<>(List.of(
                "--cli", "--json", "--silent",
                "--product", product.getFileName().toString(),
                "--flow", flow.getFileName().toString(),
                "--path", targetDir.toString(),
                "--external-download", externalDir.toString()));
        Result result = run(exe, distDir, args, sink, cancelled);
        if (result.exitCode() != 10) {
            return new ExternalCheck(result.exitCode(), List.of(), null);
        }
        Path manifest = externalDir.resolve("downloads.json");
        return new ExternalCheck(10, parseDownloads(manifest), manifest.toString());
    }

    private static List<ExternalDownload> parseDownloads(Path manifest) {
        if (manifest == null || !Files.isRegularFile(manifest)) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : root;
            if (!data.has("downloads") || !data.get("downloads").isJsonArray()) {
                return List.of();
            }
            List<ExternalDownload> downloads = new ArrayList<>();
            for (var element : data.getAsJsonArray("downloads")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String name = item.has("name") ? item.get("name").getAsString() : "";
                String url = item.has("url") ? item.get("url").getAsString() : "";
                long size = item.has("size") ? item.get("size").getAsLong() : -1;
                String step = item.has("step") ? item.get("step").getAsString() : "";
                if (!name.isBlank() && !url.isBlank()) {
                    downloads.add(new ExternalDownload(name, url, size, step));
                }
            }
            return downloads;
        } catch (Exception e) {
            LOG.warn("[NSUM] 解析 HIC 外部下载清单失败: {}", e.toString());
            return List.of();
        }
    }

    /// 用 CLI 分发目录安装（目录含 hci_cli.exe + product.json + install.json + deploy/）。
    public static Result installFromDist(Path distDir, Path targetDir, ProgressSink sink) {
        return installFromDist(distDir, targetDir, sink, () -> false);
    }

    public static Result installFromDist(Path distDir, Path targetDir, ProgressSink sink,
                                         BooleanSupplier cancelled) {
        if (distDir == null || !Files.isDirectory(distDir)) {
            return new Result(false, -1, "CLI 分发目录不存在: " + distDir);
        }
        Path exe = distDir.resolve("hci_cli.exe");
        Path product = distDir.resolve("product.json");
        Path flow = distDir.resolve("install.json");
        if (!Files.isRegularFile(exe) || !Files.isRegularFile(product)
                || !Files.isRegularFile(flow)) {
            return new Result(false, -1,
                    "CLI 分发目录缺少 hci_cli.exe/product.json/install.json: " + distDir);
        }
        List<String> args = new ArrayList<>(List.of(
                "--cli", "--json", "--silent",
                "--product", product.getFileName().toString(),
                "--flow", flow.getFileName().toString(),
                "--path", targetDir.toString()));
        return run(exe, distDir, args, sink, cancelled);
    }

    /// 启动 HIC CLI 并解析 `=====JSON-BEGIN/END=====` 中的 progress/message/finish。
    private static Result run(Path exe, Path workDir, List<String> args, ProgressSink sink,
                              BooleanSupplier cancelled) {
        if (cancelled != null && cancelled.getAsBoolean()) {
            return new Result(false, -1, "安装已取消");
        }
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(args);
        LOG.info("[NSUM] 调用 HIC CLI 安装器: {}", String.join(" ", command));
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workDir.toFile())
                    .redirectErrorStream(true);
            Process process = builder.start();
            if (sink != null) {
                sink.progress(-1, "HIC CLI 已启动，等待安装步骤输出");
            }
            Thread cancelWatcher = new Thread(() -> {
                try {
                    while (process.isAlive()) {
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            LOG.info("[NSUM] 用户取消安装，结束 HIC CLI");
                            process.destroyForcibly();
                            return;
                        }
                        Thread.sleep(100);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "nsum-install-cancel-watcher");
            cancelWatcher.setDaemon(true);
            cancelWatcher.start();

            boolean success = false;
            boolean inJson = false;
            StringBuilder block = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        process.destroyForcibly();
                        return new Result(false, -1, "安装已取消");
                    }
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
                        if (sink != null) {
                            sink.progress(-1, describe(line));
                        }
                    }
                }
            }

            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return new Result(false, -1, "安装超时（30 分钟）");
            }
            if (cancelled != null && cancelled.getAsBoolean()) {
                return new Result(false, -1, "安装已取消");
            }
            int exit = process.exitValue();
            LOG.info("[NSUM] HIC CLI 退出码: {}", exit);
            return new Result(exit == 0, exit, exit == 0 ? "安装完成" : "安装器退出码 " + exit);
        } catch (IOException e) {
            LOG.error("[NSUM] HIC CLI 启动失败", e);
            return new Result(false, -1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, -1, "安装被中断");
        }
    }

    private static String describe(String line) {
        String text = line == null ? "" : line.trim();
        if (text.isBlank()) {
            return "等待 HIC CLI 输出";
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("step 'gitdownload'") || lower.contains("download backend 'github'")) {
            return "正在下载 Git for Windows 组件（HIC）";
        }
        if (lower.contains("step 'gitplan'")) {
            return "检查 Git 组件";
        }
        if (lower.contains("step 'lang'")) {
            return "初始化安装语言";
        }
        if (lower.contains("step 'license'")) {
            return "处理许可协议";
        }
        if (lower.contains("step 'path'")) {
            return "检查安装目录";
        }
        if (lower.contains("step 'components'")) {
            return "检查组件";
        }
        if (lower.contains("download chain init")) {
            return "准备组件下载源";
        }
        if (lower.contains("download") && lower.contains("git")) {
            return "下载 Git for Windows 组件";
        }
        String cleaned = text.replaceFirst("^\\[(INFO|WARN|DEBUG|ERROR)\\]\\s*", "");
        cleaned = cleaned.replaceFirst("^step '([^']+)'", "步骤 $1");
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "…" : cleaned;
    }

    private static boolean handleEvent(String json, ProgressSink sink) {
        if (json.isBlank()) {
            return false;
        }
        try {
            JsonObject event = JsonParser.parseString(json).getAsJsonObject();
            String type = event.has("event") ? event.get("event").getAsString() : "";
            switch (type) {
                case "progress" -> {
                    int percent = event.has("percent") ? event.get("percent").getAsInt() : 0;
                    String step = event.has("step") ? event.get("step").getAsString() : "";
                    String detail = event.has("detail") ? event.get("detail").getAsString() : "";
                    if (sink != null) {
                        sink.progress(percent, (step.isEmpty() ? "" : step + " ") + detail);
                    }
                }
                case "message" -> {
                    boolean error = event.has("error") && event.get("error").getAsBoolean();
                    String text = event.has("text") ? event.get("text").getAsString() : "";
                    LOG.log(error ? org.apache.logging.log4j.Level.WARN
                            : org.apache.logging.log4j.Level.INFO, "[NSUM] [hic] {}", text);
                }
                case "finish" -> {
                    boolean ok = event.has("success") && event.get("success").getAsBoolean();
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
}
