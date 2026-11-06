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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;

/// 安装进度面板（HMCL 原生 JavaFX 自绘），用于把 HIC 安装器的进度回显到 GUI。
public final class NSumProgressPane extends VBox implements NSumHicInstaller.ProgressSink {

    private final Label title;
    private final Label detail;
    private final ProgressBar bar;

    public NSumProgressPane(String header) {
        super(10);
        setPadding(new Insets(16));
        setPrefWidth(460);

        title = new Label(header);
        title.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        detail = new Label("准备中…");
        detail.setWrapText(true);
        bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(title, bar, detail);
    }

    @Override
    public void progress(int percent, String text) {
        Runnable update = () -> {
            bar.setProgress(Math.max(0, Math.min(100, percent)) / 100.0);
            if (text != null && !text.isBlank()) {
                detail.setText(text);
            }
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    public void finish(String text) {
        runOnFx(() -> {
            title.setText("完成");
            progress(100, text);
        });
    }

    public void fail(String text) {
        runOnFx(() -> {
            title.setText("失败");
            progress(0, text);
        });
    }

    private static void runOnFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
