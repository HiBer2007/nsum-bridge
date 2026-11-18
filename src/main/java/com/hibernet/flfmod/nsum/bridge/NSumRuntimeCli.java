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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// 以 `--json` 调用 NSUM Runtime CLI 并解析 `=====JSON-BEGIN=====` 块。
///
/// <p>启动向导用它拿「真实的」Git 分支列表与整合包分支列表，而不是凭实例里
/// 记录的旧值猜。CLI 会在需要时自行克隆/刷新仓库缓存。</p>
final class NSumRuntimeCli {

    private static final Logger LOG = LogManager.getLogger(NSumRuntimeCli.class);
    private static final String BEGIN = "=====JSON-BEGIN=====";
    private static final String END = "=====JSON-END=====";

    private NSumRuntimeCli() {
    }

    static Optional<JsonObject> runJson(List<String> arguments, Consumer<String> onLine, long timeoutSeconds)
            throws IOException, InterruptedException {
        NSumRuntime.Status status = NSumRuntime.status();
        if (!status.installed()) {
            return Optional.empty();
        }
        List<String> command = new ArrayList<>();
        command.add(NSumPaths.runtimeExe().toString());
        command.addAll(arguments);

        LOG.info("[NSUM] CLI: {}", String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(NSumPaths.hmclHome().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder all = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
                if (onLine != null) {
                    try {
                        onLine.accept(line);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("CLI 超时（" + timeoutSeconds + " 秒）");
        }
        int exit = process.exitValue();
        int begin = all.indexOf(BEGIN);
        int end = all.indexOf(END);
        if (begin < 0 || end <= begin) {
            LOG.warn("[NSUM] CLI 未输出 JSON 块（exit={}）: {}", exit, tail(all.toString()));
            return Optional.empty();
        }
        String json = all.substring(begin + BEGIN.length(), end).trim();
        try {
            return Optional.of(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            LOG.warn("[NSUM] CLI JSON 解析失败: {}", e.toString());
            return Optional.empty();
        }
    }

    static Optional<JsonObject> gitBranches(String repo, Consumer<String> onLine)
            throws IOException, InterruptedException {
        return runJson(List.of("info", "git-branches", "--repo", repo, "--json"), onLine, 900);
    }

    static Optional<JsonObject> modpacks(String repo, String gitBranch, Consumer<String> onLine)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of("info", "modpacks", "--repo", repo, "--json"));
        if (gitBranch != null && !gitBranch.isBlank()) {
            args.add("--git-branch");
            args.add(gitBranch);
        }
        return runJson(args, onLine, 900);
    }

    private static String tail(String text) {
        String compact = text.replace("\u001b", "").trim();
        return compact.length() <= 400 ? compact : compact.substring(compact.length() - 400);
    }

    static boolean runtimePresent() {
        return Files.isRegularFile(NSumPaths.runtimeExe());
    }
}
