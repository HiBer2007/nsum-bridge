/*
 * NSUM Bridge — HMCL integration for NeoServerUpdateModpack
 * Copyright (C) 2026 HiBERNET
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package com.hibernet.flfmod.nsum.bridge.mixin;

import com.hibernet.flfmod.api.game.FLFGames;
import com.hibernet.flfmod.nsum.bridge.NSumBranchSelection;
import com.hibernet.flfmod.nsum.bridge.NSumBranchSelectTask;
import com.hibernet.flfmod.nsum.bridge.NSumBranchWizard;
import com.hibernet.flfmod.nsum.bridge.NSumConfig;
import com.hibernet.flfmod.nsum.bridge.NSumInstance;
import com.hibernet.flfmod.nsum.bridge.NSumSyncTask;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.GameInstanceManifest;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// 把 NSUM 更新检查插到启动任务链最前面。
///
/// <p>HMCL 的启动任务是 `checkGameState(...) -> 依赖 -> 登录 -> 等待游戏启动`；
/// 这里包裹 `checkGameState` 的返回值，让 `NSumSyncTask`（三元组校验/切换 +
/// 内容同步）在**第一步**执行，完成之后才继续原生启动步骤。</p>
@Mixin(value = LauncherHelper.class, remap = false)
public abstract class MixinLauncherHelper {

    @Inject(method = "checkGameState", at = @At("RETURN"), cancellable = true, remap = false)
    private void nsum$prependUpdateCheck(HMCLGameInstance gameInstance,
                                         GameSettings.Effective setting,
                                         GameInstanceManifest manifest,
                                         CallbackInfoReturnable<Task<JavaRuntime>> cir) {
        Task<JavaRuntime> original = cir.getReturnValue();
        if (original == null || gameInstance == null || gameInstance.getId() == null) {
            return;
        }
        org.jackhuang.hmcl.game.HMCLGameRepository repository =
                (org.jackhuang.hmcl.game.HMCLGameRepository) gameInstance.getRepository();
        String versionId = gameInstance.getId().id();
        java.nio.file.Path versionRoot = repository.getInstanceRoot(gameInstance.getId());
        if (!versionRoot.resolve(".NSUM").toFile().isDirectory()) {
            return;
        }
        boolean wizard = NSumInstance.read(versionRoot).isPresent()
                && NSumConfig.load().isBranchWizard();
        Task<Void> first = wizard
                ? new NSumBranchSelectTask(FLFGames.wrap(repository), versionId)
                : new NSumSyncTask(FLFGames.wrap(repository), versionId);
        Task<JavaRuntime> wrapped = first
                .withStage("NSUM 更新检查")
                .thenComposeAsync(ignored -> wizard ? new NSumSyncTask(FLFGames.wrap(repository), versionId) : null)
                .thenComposeAsync(ignored -> original);
        cir.setReturnValue(wrapped);
    }
}
