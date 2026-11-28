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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/// 启动前的三步分支选择：Git 仓库分支 → 整合包分支 → 确认。
///
/// <p>界面直接嵌入 HMCL 的启动任务对话框（不新开窗口），由
/// {@link NSumBranchSelectTask} 负责挂载并在任务线程上等待用户确认。
/// 列表内容由 Runtime CLI 的 `info git-branches --json` /
/// `info modpacks --json` 现场解析，显示真实分支与元数据。</p>
public final class NSumBranchWizard {

    private static final Logger LOG = LogManager.getLogger(NSumBranchWizard.class);

    private record BranchOption(String name, String type, String gameVersion,
                                String modloader, String modloaderVersion, String core) {
    }

    private final Path versionRoot;
    private final NSumInstance instance;
    private final CompletableFuture<NSumBranchSelection> result = new CompletableFuture<>();

    private final VBox root = new VBox(6);
    private final Label stepLabel = new Label();
    private final Label status = new Label();
    private final ListView<String> list = new ListView<>();
    private final VBox summaryBox = new VBox(6);
    private final Button previous = new Button("上一步");
    private final Button next = new Button("下一步");
    private final Button reload = new Button("重新加载");
    private final Button cancel = new Button("取消");

    private final List<String> gitBranches = new ArrayList<>();
    private final List<BranchOption> modpackBranches = new ArrayList<>();
    /// Repository address that actually answered (empty until a candidate worked).
    private volatile String resolvedRepo = "";
    private String selectedGitBranch = "";
    private String selectedModpack = "";
    private int page = 0;
    private volatile boolean loading;

    private NSumBranchWizard(Path versionRoot, NSumInstance instance) {
        this.versionRoot = versionRoot;
        this.instance = instance;
    }

    /// 必须在 FX 线程调用；面板挂到启动对话框后返回，等用户在面板里确认或取消。
    public static CompletableFuture<NSumBranchSelection> start(Path versionRoot, NSumInstance instance) {
        NSumBranchWizard wizard = new NSumBranchWizard(versionRoot, instance);
        wizard.build();
        return wizard.result;
    }

    private void build() {
        root.setPadding(new Insets(10, 14, 4, 14));
        root.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 4;");

        Label title = new Label("NSUM 选择启动分支");
        title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
        stepLabel.setStyle("-fx-text-fill: #8a8a8a; -fx-font-size: 10px;");
        HBox header = new HBox(8, title, stepLabel);
        header.setAlignment(Pos.CENTER_LEFT);

        list.setPrefHeight(150);
        list.setMinHeight(120);
        summaryBox.setVisible(false);
        summaryBox.setManaged(false);

        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #8a8a8a; -fx-font-size: 10px;");

        previous.setOnAction(e -> goPrevious());
        next.setDefaultButton(true);
        next.setOnAction(e -> goNext());
        reload.setOnAction(e -> reloadCurrentPage());
        cancel.setOnAction(e -> close(null));
        previous.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(6, reload, spacer, previous, next, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().setAll(header, list, summaryBox, status, buttons);
        VBox.setVgrow(list, Priority.ALWAYS);

        NSumLaunchDialog panel = NSumLaunchDialog.mount();
        panel.mountSelectionPanel(root);
        panel.setStatus("选择 Git 分支与整合包分支…");

        selectedGitBranch = instance.gitBranch == null ? "" : instance.gitBranch;
        selectedModpack = instance.branch == null ? "" : instance.branch;
        loadGitBranches();
    }

    private void loadGitBranches() {
        page = 0;
        renderPage();
        List<String> candidates = NSumRepoResolver.candidates(instance);
        if (candidates.isEmpty()) {
            String primary = instance.repo();
            if (primary.isBlank()) {
                status.setText("实例未配置仓库地址");
                return;
            }
            candidates = List.of(primary);
        }
        List<String> repoCandidates = candidates;
        loading = true;
        updateButtons();
        status.setText("正在通过 Runtime 解析 Git 分支…");
        CompletableFuture.runAsync(() -> {
            List<String> branches = new ArrayList<>();
            String error = null;
            String working = "";
            boolean probe = repoCandidates.size() > 1;
            for (int index = 0; index < repoCandidates.size() && branches.isEmpty(); index++) {
                String candidate = repoCandidates.get(index);
                if (index > 0) {
                    appendProgress("仓库地址不可用，尝试下一个：" + candidate);
                }
                if (probe && !NSumGit.reachable(candidate, NSumGit.PROBE_TIMEOUT_SECONDS)) {
                    error = "仓库地址不可达：" + candidate;
                    continue;
                }
                try {
                    // Reuse an existing checkout of another address instead of cloning a copy.
                    String request = NSumRepoResolver.requestAddressFor(instance, candidate);
                    Optional<JsonObject> json = NSumRuntimeCli.gitBranches(request, this::appendProgress);
                    if (json.isPresent() && json.get().has("data")) {
                        JsonArray array = json.get().getAsJsonObject("data").getAsJsonArray("branches");
                        for (JsonElement element : array) {
                            branches.add(element.getAsString());
                        }
                    }
                    if (branches.isEmpty()) {
                        error = "Runtime 未返回任何 Git 分支（" + candidate + "）";
                    } else {
                        working = request;
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                    branches.clear();
                }
            }
            List<String> loaded = branches;
            String failure = error;
            String resolved = working;
            Platform.runLater(() -> {
                loading = false;
                resolvedRepo = resolved;
                gitBranches.clear();
                gitBranches.addAll(loaded);
                list.getItems().setAll(loaded);
                if (failure != null) {
                    status.setText("解析 Git 分支失败：" + failure);
                    updateButtons();
                    return;
                }
                selectGitDefault();
                status.setText("Git 分支 " + loaded.size() + " 个"
                        + (selectedGitBranch.isBlank() ? "" : "，当前选择：" + selectedGitBranch));
                updateButtons();
                if (loaded.size() == 1) {
                    goNext();
                }
            });
        });
    }

    private void selectGitDefault() {
        int index = gitBranches.indexOf(selectedGitBranch);
        if (index < 0) {
            index = gitBranches.indexOf("main");
            if (index < 0 && !gitBranches.isEmpty()) {
                index = 0;
            }
        }
        if (index >= 0) {
            list.getSelectionModel().select(index);
            selectedGitBranch = gitBranches.get(index);
        }
    }

    private void loadModpacks() {
        page = 1;
        renderPage();
        if (selectedGitBranch.isBlank()) {
            status.setText("请先选择 Git 分支");
            return;
        }
        loading = true;
        updateButtons();
        status.setText("正在解析整合包分支（Git 分支 " + selectedGitBranch + "）…");
        String repo = activeRepo();
        String gitBranch = selectedGitBranch;
        CompletableFuture.runAsync(() -> {
            List<BranchOption> options = new ArrayList<>();
            String error = null;
            try {
                Optional<JsonObject> json = NSumRuntimeCli.modpacks(repo, gitBranch, this::appendProgress);
                if (json.isPresent()) {
                    JsonArray array = json.get().getAsJsonObject("data").getAsJsonArray("branches");
                    for (JsonElement element : array) {
                        JsonObject branch = element.getAsJsonObject();
                        boolean hidden = branch.has("hidden") && branch.get("hidden").getAsBoolean();
                        if (hidden) {
                            continue;
                        }
                        options.add(new BranchOption(
                                string(branch, "name"),
                                string(branch, "type"),
                                string(branch, "game_version"),
                                string(branch, "modloader"),
                                string(branch, "modloader_version"),
                                string(branch, "core")));
                    }
                }
                if (options.isEmpty()) {
                    error = "该 Git 分支下没有可见的整合包分支";
                }
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<BranchOption> loaded = options;
            String failure = error;
            Platform.runLater(() -> {
                loading = false;
                modpackBranches.clear();
                modpackBranches.addAll(loaded);
                List<String> display = new ArrayList<>();
                for (BranchOption option : loaded) {
                    display.add(describe(option));
                }
                list.getItems().setAll(display);
                if (failure != null) {
                    status.setText("解析整合包分支失败：" + failure);
                    updateButtons();
                    return;
                }
                selectModpackDefault();
                status.setText("整合包分支 " + loaded.size() + " 个，当前选择：" + selectedModpack);
                updateButtons();
            });
        });
    }

    private void selectModpackDefault() {
        int index = -1;
        for (int i = 0; i < modpackBranches.size(); i++) {
            if (modpackBranches.get(i).name.equals(selectedModpack)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            index = 0;
        }
        if (index >= 0 && index < modpackBranches.size()) {
            list.getSelectionModel().select(index);
            selectedModpack = modpackBranches.get(index).name;
        }
    }

    private void goNext() {
        if (page == 0) {
            if (selectedGitBranch.isBlank()) {
                status.setText("请选择一个 Git 分支");
                return;
            }
            if (modpackBranches.isEmpty()) {
                loadModpacks();
            } else {
                page = 1;
                renderPage();
            }
            return;
        }
        if (page == 1) {
            if (selectedModpack.isBlank()) {
                status.setText("请选择一个整合包分支");
                return;
            }
            page = 2;
            renderPage();
            return;
        }
        accept();
    }

    private void goPrevious() {
        if (loading) {
            return;
        }
        if (page == 2) {
            page = 1;
        } else if (page == 1) {
            page = 0;
        }
        renderPage();
    }

    private void reloadCurrentPage() {
        if (page == 0) {
            loadGitBranches();
        } else if (page == 1) {
            modpackBranches.clear();
            loadModpacks();
        }
    }

    private void renderPage() {
        boolean listVisible = page < 2;
        list.setVisible(listVisible);
        list.setManaged(listVisible);
        summaryBox.setVisible(!listVisible);
        summaryBox.setManaged(!listVisible);
        previous.setDisable(page == 0);
        if (page < 2) {
            stepLabel.setText("第 " + (page + 1) + " / 3 步");
            next.setText("下一步");
        } else {
            stepLabel.setText("第 3 / 3 步");
            next.setText("开始同步");
            renderSummary();
        }
        updateButtons();
    }

    private void renderSummary() {
        BranchOption option = currentOption();
        summaryBox.getChildren().setAll(
                row("仓库", activeRepo()),
                row("Git 分支", selectedGitBranch),
                row("整合包分支", selectedModpack),
                row("分支类型", option == null ? "" : option.type),
                row("游戏版本", option == null ? "" : option.gameVersion),
                row("加载器", option == null ? "" : (option.modloader + " " + option.modloaderVersion)),
                row("服务端核心", option == null || option.core.isBlank() ? "（非服务端专用包）" : option.core));
    }

    private BranchOption currentOption() {
        for (BranchOption option : modpackBranches) {
            if (option.name.equals(selectedModpack)) {
                return option;
            }
        }
        return null;
    }

    private static HBox row(String label, String value) {
        Label key = new Label(label + "：");
        key.setMinWidth(88);
        key.setStyle("-fx-text-fill: #8a8a8a; -fx-font-size: 10px;");
        Label text = new Label(value == null ? "" : value);
        text.setWrapText(true);
        text.setStyle("-fx-font-size: 10px;");
        HBox box = new HBox(6, key, text);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private void accept() {
        BranchOption option = currentOption();
        NSumBranchSelection selection = new NSumBranchSelection(
                activeRepo(),
                selectedGitBranch,
                selectedModpack,
                option == null ? instance.branchType : option.type,
                option == null ? instance.gameVersion : option.gameVersion,
                option == null ? instance.modloader : option.modloader,
                option == null ? instance.modloaderVersion : option.modloaderVersion,
                option == null ? instance.core : option.core);
        close(selection);
    }

    /// Repository address to use right now: the one a candidate probe resolved, else the
    /// instance's primary address.
    private String activeRepo() {
        String resolved = resolvedRepo;
        return resolved == null || resolved.isBlank() ? instance.repo() : resolved;
    }

    private void close(NSumBranchSelection selection) {
        NSumLaunchDialog.unmountSelectionPanel();
        result.complete(selection);
    }

    private void appendProgress(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String text = line.replace("\u001b", "").trim();
        if (text.isEmpty() || text.startsWith("=")) {
            return;
        }
        Platform.runLater(() -> status.setText(text.length() > 140 ? text.substring(text.length() - 140) : text));
    }

    private void updateButtons() {
        previous.setDisable(loading || page == 0);
        next.setDisable(loading
                || (page == 0 ? selectedGitBranch.isBlank() : page == 1 ? selectedModpack.isBlank() : false));
        reload.setDisable(loading || page == 2);
        cancel.setDisable(false);
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static String describe(BranchOption option) {
        StringBuilder text = new StringBuilder(option.name);
        if (!option.gameVersion.isBlank()) {
            text.append("  ·  ").append(option.gameVersion);
        }
        if (!option.modloader.isBlank()) {
            text.append(' ').append(option.modloader).append(' ').append(option.modloaderVersion);
        }
        if (!option.core.isBlank()) {
            text.append("  ·  ").append(option.core);
        }
        if (!option.type.isBlank() && !"universal".equalsIgnoreCase(option.type)) {
            text.append("  [").append(option.type).append(']');
        }
        return text.toString();
    }
}
