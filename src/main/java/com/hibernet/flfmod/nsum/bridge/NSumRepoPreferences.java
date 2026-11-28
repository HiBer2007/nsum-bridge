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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/// User configuration for repository address selection.
///
/// <p>File: `.hmcl/nsum/repo.properties` (optional). Keys:</p>
/// <ul>
///   <li>`preference` — `auto` (fastest, default), `ssh`, `https`, `http`, `manual`, or an
///       explicit URL (`git@host:owner/repo.git`, `https://...`);</li>
///   <li>`probeTimeoutSeconds` — per-address probe timeout (default 8);</li>
///   <li>`syncTimeoutSeconds` — per-address sync timeout before falling back (default 300).</li>
/// </ul>
///
/// <p>System property / environment overrides: `nsum.repo.preference`,
/// `nsum.repo.probeTimeoutSeconds`, `nsum.repo.syncTimeoutSeconds`,
/// `NSUM_REPO_PREFERENCE`, `NSUM_REPO_PROBE_TIMEOUT`, `NSUM_REPO_SYNC_TIMEOUT`.</p>
final class NSumRepoPreferences {

    private static final int DEFAULT_PROBE_SECONDS = 8;
    private static final int DEFAULT_SYNC_SECONDS = 300;

    private NSumRepoPreferences() {
    }

    static Path file() {
        return NSumPaths.nsumDir().resolve("repo.properties");
    }

    static String preference() {
        String value = System.getProperty("nsum.repo.preference");
        if (value == null) {
            value = System.getenv("NSUM_REPO_PREFERENCE");
        }
        if (value == null) {
            value = read("preference");
        }
        return value == null || value.isBlank() ? "auto" : value.trim();
    }

    static int probeTimeoutSeconds() {
        return positive("nsum.repo.probeTimeoutSeconds", "NSUM_REPO_PROBE_TIMEOUT",
                "probeTimeoutSeconds", DEFAULT_PROBE_SECONDS);
    }

    static int cloneTimeoutSeconds() {
        return positive("nsum.repo.cloneTimeoutSeconds", "NSUM_REPO_CLONE_TIMEOUT",
                "cloneTimeoutSeconds", 180);
    }

    static int syncTimeoutSeconds() {
        return positive("nsum.repo.syncTimeoutSeconds", "NSUM_REPO_SYNC_TIMEOUT",
                "syncTimeoutSeconds", DEFAULT_SYNC_SECONDS);
    }

    private static int positive(String property, String env, String key, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            value = System.getenv(env);
        }
        if (value == null) {
            value = read(key);
        }
        if (value == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String read(String key) {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties.getProperty(key);
        } catch (IOException ignored) {
            return null;
        }
    }
}
