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

import com.hibernet.flfmod.nsum.bridge.NSumRepoActions;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// 在 HMCL「安装整合包」入口追加两个新建实例选项：
/// 「克隆 NSUM 仓库」与「创建 NSUM 仓库」。
///
/// <p>两者都会把仓库放到 `.hmcl/nsum/repos/`，随后直接拉起 Runtime 自带的
/// NSUM 编辑器，让用户从仓库开始配置整合包（游戏/加载器由 NSUM 启动时安装）。</p>
@Mixin(targets = "org.jackhuang.hmcl.ui.download.ModpackSelectionPage", remap = false)
public abstract class MixinModpackSelectionPage extends VBox {

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void nsum$addRepoOptions(CallbackInfo ci) {
        Button clone = new Button("克隆 NSUM 仓库");
        clone.setMaxWidth(Double.MAX_VALUE);
        clone.getStyleClass().add("jfx-button");
        clone.setOnAction(e -> NSumRepoActions.promptClone());
        Button create = new Button("创建 NSUM 仓库");
        create.setMaxWidth(Double.MAX_VALUE);
        create.getStyleClass().add("jfx-button");
        create.setOnAction(e -> NSumRepoActions.promptCreate());
        getChildren().addAll(clone, create);
        for (Node node : getChildren()) {
            if (node instanceof Button button) {
                button.getStyleClass().add("jfx-button");
            }
        }
    }
}
