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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `instance.json` round-trip of the repository address list.
class NSumInstanceRepoUrlsTest {

    private static final String SSH = "git@github.com:HiBer2007/NeoServerUpdateModpack.git";
    private static final String HTTPS = "https://github.com/HiBer2007/NeoServerUpdateModpack.git";

    @TempDir
    Path temp;

    @Test
    void keepsOrderedAddressesAndPrimaryGetter() throws Exception {
        NSumInstance instance = new NSumInstance();
        instance.repo = HTTPS;
        instance.repoUrls = List.of(new NSumRepoUrl("ssh", SSH), new NSumRepoUrl("https", HTTPS));
        instance.branch = "base";

        instance.write(temp);

        NSumInstance read = NSumInstance.read(temp).orElseThrow();
        assertEquals(List.of(SSH, HTTPS), NSumRepoUrls.urls(read.repoUrls));
        assertEquals(List.of(SSH, HTTPS), read.orderedRepoUrls());
        // repo() always reports the first manifest address, the legacy field is a fallback.
        assertEquals(SSH, read.repo());
        assertEquals(HTTPS, read.repo);
    }

    @Test
    void legacyInstanceMarkerStillResolves() throws Exception {
        Path marker = temp.resolve(".NSUM").resolve("instance.json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, """
                {"schema": 1, "repo": "%s", "branch": "base"}
                """.formatted(SSH), StandardCharsets.UTF_8);

        NSumInstance read = NSumInstance.read(temp).orElseThrow();

        assertEquals(SSH, read.repo());
        assertEquals(List.of(SSH), read.orderedRepoUrls());
        assertEquals(NSumRepoUrl.TYPE_SSH, read.repoUrls.get(0).type());
    }

    @Test
    void rememberedAddressIsTriedFirst() throws Exception {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(new NSumRepoUrl("https", HTTPS), new NSumRepoUrl("ssh", SSH));

        instance.setActiveRepo(SSH);
        instance.write(temp);

        NSumInstance read = NSumInstance.read(temp).orElseThrow();
        assertEquals(SSH, read.activeRepoUrl);
        assertEquals(List.of(SSH, HTTPS), read.orderedRepoUrls());
    }

    @Test
    void legacyRepoFieldIsNotDuplicated() throws Exception {
        NSumInstance instance = new NSumInstance();
        instance.repo = SSH;
        instance.repoUrls = List.of(new NSumRepoUrl("ssh", SSH), new NSumRepoUrl("https", HTTPS));

        instance.write(temp);

        NSumInstance read = NSumInstance.read(temp).orElseThrow();
        assertEquals(List.of(SSH, HTTPS), read.orderedRepoUrls());
    }

    @Test
    void markerContainsTypedAddressArray() throws Exception {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(new NSumRepoUrl("ssh", SSH), new NSumRepoUrl("https", HTTPS));

        instance.write(temp);

        String text = Files.readString(temp.resolve(".NSUM").resolve("instance.json"), StandardCharsets.UTF_8);
        JsonObject marker = JsonParser.parseString(text).getAsJsonObject();
        assertEquals(2, marker.getAsJsonArray("repoUrls").size());
        assertEquals("ssh", marker.getAsJsonArray("repoUrls").get(0).getAsJsonObject()
                .get("type").getAsString());
        assertEquals(SSH, marker.getAsJsonArray("repoUrls").get(0).getAsJsonObject()
                .get("url").getAsString());
        assertTrue(marker.has("activeRepoUrl"));
    }

    @Test
    void hasRepoReflectsFirstAddress() {
        NSumInstance instance = new NSumInstance();
        assertTrue(!instance.hasRepo());
        instance.repoUrls = List.of(new NSumRepoUrl("ssh", SSH));
        assertTrue(instance.hasRepo());
    }
}
