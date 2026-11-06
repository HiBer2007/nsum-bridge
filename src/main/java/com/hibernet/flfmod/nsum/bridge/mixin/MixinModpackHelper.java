/*
 * NSUM Bridge — HMCL integration for NeoServerUpdateModpack
 * Copyright (C) 2026 HiBerNET
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 2.1 of the License, or
 * (at your option) any later version.
 */
package com.hibernet.flfmod.nsum.bridge.mixin;

import org.jackhuang.hmcl.game.ModpackHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

/// 让 HMCL 接受 `.nsumpack` 作为整合包扩展名。
///
/// <p>HMCL 原生只认 `zip` / `mrpack`（`ModpackHelper.isFileModpackByExtension`），
/// 导入文件选择与后续解析都依赖它；这里把 `nsumpack` 也纳入。</p>
@Mixin(value = ModpackHelper.class, remap = false)
public abstract class MixinModpackHelper {

    @Inject(method = "isFileModpackByExtension", at = @At("HEAD"), cancellable = true, remap = false)
    private static void nsum$acceptNsumpack(Path file, CallbackInfoReturnable<Boolean> cir) {
        if (file == null) {
            return;
        }
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".nsumpack")) {
            cir.setReturnValue(true);
        }
    }
}
