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

import java.util.Locale;

/// One repository address of an NSUM pack.
///
/// <p>Mirrors the `repository.urls[]` entry of `nsum.index.json`
/// (`{"type": "ssh"|"https"|"http", "url": "..."}`). Legacy packs that only carry
/// `repository.url` or `instance.repo` are converted into the same shape, so clone and
/// sync code can always iterate a single, ordered list.</p>
///
/// @param type address kind: `ssh`, `https`, `http` or `local` for filesystem paths
/// @param url  address as written in the manifest (trimmed)
public record NSumRepoUrl(String type, String url) {

    public static final String TYPE_SSH = "ssh";
    public static final String TYPE_HTTPS = "https";
    public static final String TYPE_HTTP = "http";
    public static final String TYPE_LOCAL = "local";

    public NSumRepoUrl {
        String value = url == null ? "" : url.trim();
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        url = value;
        type = kind.isEmpty() ? NSumRepoUrls.inferType(value) : kind;
    }

    public static NSumRepoUrl of(String url) {
        return new NSumRepoUrl("", url);
    }

    public boolean present() {
        return !url.isEmpty();
    }

    @Override
    public String toString() {
        return url;
    }
}
