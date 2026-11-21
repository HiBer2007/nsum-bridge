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

import com.hibernet.flfmod.api.FLFDialogs;
import javafx.application.Platform;
import javafx.scene.control.TextInputDialog;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/// 「克隆 NSUM 仓库 / 创建 NSUM 仓库」两个新实例入口。
///
/// <p>两者都会把仓库克隆/初始化到 `.hmcl/nsum/repos/&lt;name&gt;`，随后直接拉起
/// NSUM 编辑器（Runtime 自带），让用户从空白或已有仓库开始配置整合包。</p>
public final class NSumRepoActions {

    private static final Logger LOG = LogManager.getLogger(NSumRepoActions.class);

    private NSumRepoActions() {
    }

    public static void promptClone() {
        TextInputDialog urlDialog = new TextInputDialog("git@github.com:HiBer2007/NeoServerUpdateModpack.git");
        urlDialog.setTitle("克隆 NSUM 仓库");
        urlDialog.setHeaderText("输入要克隆的 Git 仓库地址");
        urlDialog.setContentText("仓库地址：");
        Optional<String> url = urlDialog.showAndWait();
        if (url.isEmpty() || url.get().isBlank()) {
            return;
        }
        TextInputDialog nameDialog = new TextInputDialog("NSUM-Repo");
        nameDialog.setTitle("克隆 NSUM 仓库");
        nameDialog.setHeaderText("本地目录名（位于 .hmcl/nsum/repos/ 下）");
        nameDialog.setContentText("目录名：");
        Optional<String> name = nameDialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        Path target = NSumPaths.reposDir().resolve(safeName(name.get().trim()));
        clone(url.get().trim(), target);
    }

    public static void promptCreate() {
        TextInputDialog nameDialog = new TextInputDialog("NSUM-Repo");
        nameDialog.setTitle("创建 NSUM 仓库");
        nameDialog.setHeaderText("新建仓库目录名（位于 .hmcl/nsum/repos/ 下）");
        nameDialog.setContentText("目录名：");
        Optional<String> name = nameDialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        Path target = NSumPaths.reposDir().resolve(safeName(name.get().trim()));
        create(target);
    }

    private static void clone(String url, Path target) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(target.getParent());
                if (Files.isDirectory(target) && Files.isRegularFile(target.resolve("workspace.json"))) {
                    Platform.runLater(() -> dialog("目录已存在：" + target, FLFDialogs.Type.WARNING));
                    NSumEditor.open(target.toString());
                    return;
                }
                List<String> command = new ArrayList<>();
                command.add(gitExecutable());
                command.add("clone");
                command.add(url);
                command.add(target.toString());
                int exit = run(command);
                if (exit != 0) {
                    Platform.runLater(() -> dialog("克隆失败（git exit " + exit + "）：" + url,
                            FLFDialogs.Type.ERROR));
                    return;
                }
                Platform.runLater(() -> {
                    dialog("仓库已克隆：" + target, FLFDialogs.Type.SUCCESS);
                    NSumEditor.open(target.toString());
                });
            } catch (Exception e) {
                LOG.error("[NSUM] 克隆仓库失败", e);
                Platform.runLater(() -> dialog("克隆失败：" + e.getMessage(), FLFDialogs.Type.ERROR));
            }
        });
    }

    private static void create(Path target) {
        CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(target);
                Path workspace = target.resolve("workspace.json");
                if (!Files.isRegularFile(workspace)) {
                    Files.writeString(workspace, TEMPLATE, StandardCharsets.UTF_8);
                }
                int exit = run(List.of(gitExecutable(), "init", "-b", "main", target.toString()));
                if (exit != 0) {
                    LOG.warn("[NSUM] git init 失败（exit={}），仓库仍可作为本地目录使用", exit);
                }
                Platform.runLater(() -> {
                    dialog("仓库已创建：" + target + "\n\n已打开 NSUM 编辑器，可直接配置分支与同步规则。",
                            FLFDialogs.Type.SUCCESS);
                    NSumEditor.open(target.toString());
                });
            } catch (Exception e) {
                LOG.error("[NSUM] 创建仓库失败", e);
                Platform.runLater(() -> dialog("创建失败：" + e.getMessage(), FLFDialogs.Type.ERROR));
            }
        });
    }

    private static int run(List<String> command) throws IOException, InterruptedException {
        LOG.info("[NSUM] 执行: {}", String.join(" ", command));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0) {
            LOG.warn("[NSUM] 命令失败（exit={}）: {}", exit, output);
        }
        return exit;
    }

    private static String gitExecutable() {
        Path dir = NSumPaths.runtimeDir().resolve("tools").resolve("git");
        for (String relative : List.of("cmd/git.exe", "bin/git.exe", "mingw64/bin/git.exe", "ucrt64/bin/git.exe")) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "git";
    }

    private static String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    private static void dialog(String text, FLFDialogs.Type type) {
        FLFDialogs.show(type, "NSUM", text);
    }

    private static final String TEMPLATE = """
            {
              "version": "1.0",
              "workspace": {
                "name": "New NSUM Modpack",
                "minecraft": "1.21.1",
                "modloader": "fabric",
                "modloader_version": ""
              },
              "git": { "remote": "", "default_branch": "main" },
              "branches": [
                { "name": "base", "parent": "", "description": "root branch", "hidden": false, "type": "universal" }
              ],
              "sync_policies": {
                "default_folder_policy": "incremental_add",
                "folders": { "config": "mirror" },
                "files": {}
              },
              "export": {
                "metadata": {
                  "name": "New NSUM Modpack",
                  "version": "1.0.0",
                  "author": ""
                }
              }
            }
            """;
}
