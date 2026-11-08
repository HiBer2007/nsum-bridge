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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// 把 NSUM 同步步骤注入 HMCL 的「启动游戏」进度对话框。
///
/// <p>不新开窗口：在 `TaskExecutorDialogPane`（启动任务对话框）的内容区下方追加
/// 一段「NSUM 同步」区域，显示当前阶段（版本检查 / 安装切换 / 内容同步）与百分比。
/// 同步结束后保留最后状态，随启动对话框一起关闭。</p>
final class NSumLaunchDialog {

    private static final Logger LOG = LogManager.getLogger(NSumLaunchDialog.class);

    private static final Pattern STEP = Pattern.compile("\\[([A-Za-z_]+)\\s+(\\d+)%]\\s*(.*)");
    private static final Pattern BAR = Pattern.compile("(\\d+)%\\s*\\|\\s*([^\\[]*)");

    private final AtomicReference<Label> statusRef = new AtomicReference<>();
    private final AtomicReference<Label> detailRef = new AtomicReference<>();
    private final AtomicReference<ProgressBar> barRef = new AtomicReference<>();
    private final AtomicReference<VBox> boxRef = new AtomicReference<>();
    private volatile boolean mounted;
    private volatile String lastStatus = "准备中…";
    private volatile String lastDetail = "";
    private volatile double lastProgress = ProgressBar.INDETERMINATE_PROGRESS;

    static NSumLaunchDialog mount() {
        NSumLaunchDialog dialog = new NSumLaunchDialog();
        try {
            Platform.runLater(dialog::mountOnFx);
        } catch (Throwable t) {
            LOG.debug("[NSUM] 无法挂载启动进度: {}", t.toString());
        }
        return dialog;
    }

    private void mountOnFx() {
        try {
            Node pane = findLaunchDialog();
            if (pane == null) {
                LOG.debug("[NSUM] 未找到启动进度对话框，跳过注入");
                return;
            }
            Node center = ((javafx.scene.layout.BorderPane) pane).getCenter();
            if (!(center instanceof VBox container)) {
                LOG.debug("[NSUM] 启动对话框结构不符合预期，跳过注入");
                return;
            }

            Label title = new Label("NSUM 更新检查");
            title.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");
            Label status = new Label(lastStatus);
            Label detail = new Label(lastDetail);
            detail.setWrapText(true);
            detail.setStyle("-fx-text-fill: #8a8a8a; -fx-font-size: 10px;");
            ProgressBar bar = new ProgressBar();
            bar.setPrefWidth(420);
            bar.setProgress(lastProgress);

            VBox box = new VBox(4, title, status, bar, detail);
            box.setPadding(new Insets(12, 16, 8, 16));

            if (container.getChildren().size() > 0) {
                container.getChildren().add(1, box);
            } else {
                container.getChildren().add(box);
            }

            statusRef.set(status);
            detailRef.set(detail);
            barRef.set(bar);
            boxRef.set(box);
            mounted = true;
        } catch (Throwable t) {
            LOG.warn("[NSUM] 注入启动进度失败: {}", t.toString());
        }
    }

    private static Node findLaunchDialog() {
        for (Window window : Window.getWindows()) {
            if (window.getScene() == null) {
                continue;
            }
            Node root = window.getScene().getRoot();
            for (Node node : root.lookupAll(".task-executor-dialog-layout")) {
                if (node instanceof javafx.scene.layout.BorderPane) {
                    return node;
                }
            }
        }
        return null;
    }

    void setStatus(String text) {
        lastStatus = text;
        update(() -> {
            Label label = statusRef.get();
            if (label != null) {
                label.setText(text);
            }
        });
    }

    void setDetail(String text) {
        lastDetail = text;
        update(() -> {
            Label label = detailRef.get();
            if (label != null) {
                label.setText(text);
            }
        });
    }

    void setProgress(double progress) {
        lastProgress = progress;
        update(() -> {
            ProgressBar bar = barRef.get();
            if (bar != null) {
                bar.setProgress(progress);
            }
        });
    }

    void fail(String text) {
        setStatus(text);
        setProgress(0);
    }
    
    void finish(String text) {
        setStatus(text);
        setProgress(1);
    }

    void acceptLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String text = line.replace("\u001b", "");
        Matcher step = STEP.matcher(text);
        if (step.find()) {
            setProgress(Math.max(0, Math.min(1, Integer.parseInt(step.group(2)) / 100.0)));
            setDetail(step.group(1) + " · " + step.group(3).trim());
            return;
        }
        Matcher bar = BAR.matcher(text);
        if (bar.find()) {
            setProgress(Math.max(0, Math.min(1, Integer.parseInt(bar.group(1)) / 100.0)));
            String message = bar.group(2).trim();
            if (!message.isEmpty()) {
                setDetail(message);
            }
        }
    }

    private void update(Runnable action) {
        if (!mounted) {
            return;
        }
        try {
            if (Platform.isFxApplicationThread()) {
                action.run();
            } else {
                Platform.runLater(action);
            }
        } catch (Throwable ignored) {
        }
    }
}
