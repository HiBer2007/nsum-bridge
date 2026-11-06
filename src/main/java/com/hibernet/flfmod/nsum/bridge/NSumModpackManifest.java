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

import org.jackhuang.hmcl.modpack.ModpackManifest;
import org.jackhuang.hmcl.modpack.ModpackProvider;

/// `nsum` 类型的清单标记。
public final class NSumModpackManifest implements ModpackManifest {

    public static final NSumModpackManifest INSTANCE = new NSumModpackManifest();

    @Override
    public ModpackProvider getProvider() {
        return NSumModpackProvider.INSTANCE;
    }
}
