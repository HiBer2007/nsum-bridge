package com.hibernet.flfmod.nsum.bridge;

import com.hibernet.flfmod.api.modpack.FLFModpackManifest;
import com.hibernet.flfmod.api.modpack.FLFModpackProvider;

/// `nsum` 类型的清单标记。
public final class NSumModpackManifest implements FLFModpackManifest {

    public static final NSumModpackManifest INSTANCE = new NSumModpackManifest();

    @Override
    public FLFModpackProvider provider() {
        return NSumModpackProvider.INSTANCE;
    }
}
