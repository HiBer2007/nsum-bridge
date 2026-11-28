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

/// 启动前选择的「Git 仓库分支 + 整合包分支」及其解析结果。
///
/// <p>三步向导（Git 分支 → 整合包分支 → 确认）的产物；由 {@link NSumBranchWizard}
/// 生成，写入实例标记作为下次默认值，并在本次启动中通过 {@link #PENDING}
/// 传递给同步任务（避免 HMCL 启动线程与 UI 线程之间的字段竞争）。</p>
public final class NSumBranchSelection {

    public final String repo;
    public final String gitBranch;
    public final String modpackBranch;
    public final String branchType;
    public final String gameVersion;
    public final String modloader;
    public final String modloaderVersion;
    public final String core;

    public NSumBranchSelection(String repo, String gitBranch, String modpackBranch,
                               String branchType, String gameVersion, String modloader,
                               String modloaderVersion, String core) {
        this.repo = repo;
        this.gitBranch = gitBranch;
        this.modpackBranch = modpackBranch;
        this.branchType = branchType;
        this.gameVersion = gameVersion;
        this.modloader = modloader;
        this.modloaderVersion = modloaderVersion;
        this.core = core;
    }

    private static final ThreadLocal<NSumBranchSelection> PENDING = new ThreadLocal<>();

    public static void setPending(NSumBranchSelection selection) {
        if (selection == null) {
            PENDING.remove();
        } else {
            PENDING.set(selection);
        }
    }

    public static NSumBranchSelection peekPending() {
        return PENDING.get();
    }

    public static NSumBranchSelection takePending() {
        NSumBranchSelection selection = PENDING.get();
        PENDING.remove();
        return selection;
    }

    /// 把选择结果写回实例（同时作为下次向导的默认值）。
    public void applyTo(NSumInstance instance) {
        if (instance == null) {
            return;
        }
        if (repo != null && !repo.isBlank()) {
            // The wizard only reports an address that actually answered, so remember it.
            instance.setActiveRepo(repo);
        }
        if (gitBranch != null && !gitBranch.isBlank()) {
            instance.gitBranch = gitBranch;
        }
        if (modpackBranch != null && !modpackBranch.isBlank()) {
            instance.branch = modpackBranch;
        }
        if (branchType != null && !branchType.isBlank()) {
            instance.branchType = branchType;
        }
        if (core != null) {
            instance.core = core;
        }
        if (gameVersion != null && !gameVersion.isBlank()) {
            instance.gameVersion = gameVersion;
        }
        if (modloader != null && !modloader.isBlank()) {
            instance.modloader = modloader;
        }
        if (modloaderVersion != null) {
            instance.modloaderVersion = modloaderVersion;
        }
    }
}
