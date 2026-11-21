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

import com.hibernet.flfmod.api.game.FLFGameRepository;
import javafx.application.Platform;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jackhuang.hmcl.task.Task;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// 启动任务链第一步：在启动对话框内嵌的三步选择面板里等用户选择分支。
///
/// <p>面板挂在 HMCL 的启动任务对话框里（内部对话框），任务线程阻塞等待，
/// FX 线程保持可交互。确认后把选择写回实例并交给同步任务；取消则中止启动。</p>
public final class NSumBranchSelectTask extends Task<Void> {

    private static final Logger LOG = LogManager.getLogger(NSumBranchSelectTask.class);

    private final FLFGameRepository repository;
    private final String versionId;

    public NSumBranchSelectTask(FLFGameRepository repository, String versionId) {
        this.repository = repository;
        this.versionId = versionId;
        setName("NSUM 选择分支");
        setStage("NSUM：选择启动分支");
    }

    @Override
    public void execute() throws Exception {
        Path versionRoot = repository.instanceRoot(versionId);
        Optional<NSumInstance> read = NSumInstance.read(versionRoot);
        if (read.isEmpty()) {
            return;
        }
        NSumInstance instance = read.get();

        CompletableFuture<NSumBranchSelection> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                NSumBranchWizard.start(versionRoot, instance)
                        .whenComplete((selection, error) -> {
                            if (error != null) {
                                future.completeExceptionally(error);
                            } else {
                                future.complete(selection);
                            }
                        });
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        NSumBranchSelection selection;
        try {
            selection = future.get(30, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            throw new CancellationException("NSUM 分支选择超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            throw new IllegalStateException("NSUM 分支选择失败: " + e.getCause(), e.getCause());
        }

        if (selection == null) {
            LOG.info("[NSUM] 用户取消了启动分支选择");
            throw new CancellationException("NSUM 启动已取消");
        }
        selection.applyTo(instance);
        try {
            instance.write(versionRoot);
        } catch (Exception e) {
            LOG.warn("[NSUM] 写回分支选择失败: {}", e.toString());
        }
        NSumBranchSelection.setPending(selection);
        LOG.info("[NSUM] 启动分支：{}/{} (type={})",
                selection.gitBranch, selection.modpackBranch, selection.branchType);
        setStage("NSUM：已选择 " + selection.modpackBranch);
    }
}
