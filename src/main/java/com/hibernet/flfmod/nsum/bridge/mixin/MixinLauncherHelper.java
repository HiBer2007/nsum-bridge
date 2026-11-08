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

import com.hibernet.flfmod.nsum.bridge.NSumSyncTask;
import org.jackhuang.hmcl.download.DefaultCacheRepository;
import org.jackhuang.hmcl.download.DefaultDependencyManager;
import org.jackhuang.hmcl.setting.GameSettings;
import org.jackhuang.hmcl.game.HMCLGameRepository;
import org.jackhuang.hmcl.game.LauncherHelper;
import org.jackhuang.hmcl.game.Version;
import org.jackhuang.hmcl.setting.DownloadProviders;
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
    private static void nsum$prependUpdateCheck(HMCLGameRepository repository,
                                                GameSettings.Effective setting,
                                                Version version,
                                                CallbackInfoReturnable<Task<JavaRuntime>> cir) {
        Task<JavaRuntime> original = cir.getReturnValue();
        if (original == null || version == null || version.getId() == null) {
            return;
        }
        String versionId = version.getId();
        if (!repository.getVersionRoot(versionId).resolve(".NSUM").toFile().isDirectory()) {
            return;
        }
        DefaultDependencyManager dependencyManager = new DefaultDependencyManager(
                repository,
                DownloadProviders.getDownloadProvider(),
                new DefaultCacheRepository());
        Task<JavaRuntime> wrapped = new NSumSyncTask(dependencyManager, versionId)
                .withStage("NSUM 更新检查")
                .thenComposeAsync(ignored -> original);
        cir.setReturnValue(wrapped);
    }
}
