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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Fallback behaviour against real (local) git repositories.
class NSumRepoFallbackTest {

    @TempDir
    Path temp;

    private Path source;
    private Path sourceFallback;

    @BeforeEach
    void prepare() throws Exception {
        assumeTrue(NSumGit.available(), "git is required for the repository fallback tests");
        source = createRepository(temp.resolve("source"), "primary");
        sourceFallback = createRepository(temp.resolve("source-fallback"), "fallback");
    }

    @Test
    void cloneUsesNextAddressWhenPrimaryFails() {
        Path missing = temp.resolve("missing-repository");
        Path target = temp.resolve("target");

        NSumRepoResolver.CloneResult result = NSumRepoResolver.cloneWithFallback(
                List.of(missing.toString(), source.toString()), target, true);

        assertTrue(result.ok(), "clone should succeed through the fallback address");
        assertEquals(source.toString(), result.url());
        assertTrue(NSumGit.isRepository(target));
        assertTrue(Files.isRegularFile(target.resolve("workspace.json")));
    }

    @Test
    void failedFallbackLeavesNoRepositoryBehind() {
        Path target = temp.resolve("target");
        Path first = temp.resolve("missing-one");
        Path second = temp.resolve("missing-two");

        NSumRepoResolver.CloneResult result = NSumRepoResolver.cloneWithFallback(
                List.of(first.toString(), second.toString()), target, true);

        assertFalse(result.ok());
        assertEquals(second.toString(), result.url());
        assertFalse(NSumGit.isRepository(target));
    }

    @Test
    void existingCheckoutIsRepointedInsteadOfClonedAgain() {
        Path target = temp.resolve("target");
        assertEquals(0, NSumGit.run(List.of("clone", source.toString(), target.toString()), 120)
                .exitCode());
        assertEquals(source.toString(), NSumGit.remoteUrl(target));

        int exit = NSumGit.setRemoteUrl(target, sourceFallback.toString());

        assertEquals(0, exit);
        assertEquals(sourceFallback.toString(), NSumGit.remoteUrl(target));
        // Same directory: the fallback never creates a second clone.
        assertTrue(NSumGit.isRepository(target));
        assertFalse(Files.exists(temp.resolve("target-2")));
    }

    @Test
    void probeDetectsReachableAddress() {
        assertTrue(NSumGit.reachable(source.toString(), 30));
        assertFalse(NSumGit.reachable(temp.resolve("missing-repository").toString(), 30));
    }

    @Test
    void attemptOrderPrefersTheAddressThatAnswers() {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(
                NSumRepoUrl.of(temp.resolve("missing-repository").toString()),
                NSumRepoUrl.of(source.toString()));

        List<String> order = NSumRepoResolver.attemptOrder(instance, null);

        assertEquals(source.toString(), order.get(0));
        assertEquals(2, order.size());
    }

    @Test
    void attemptOrderKeepsRememberedAddressFirstWithoutProbing() {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(
                NSumRepoUrl.of(temp.resolve("missing-repository").toString()),
                NSumRepoUrl.of(source.toString()));
        instance.setActiveRepo(source.toString());

        List<String> order = NSumRepoResolver.attemptOrder(instance, null);

        assertEquals(source.toString(), order.get(0));
    }

    @Test
    void cloneCandidatesAddTheOtherTransport() {
        assertEquals(List.of(
                        "https://github.com/HiBer2007/NeoServerUpdateModpack.git",
                        "git@github.com:HiBer2007/NeoServerUpdateModpack.git"),
                NSumRepoResolver.cloneCandidates(
                        "https://github.com/HiBer2007/NeoServerUpdateModpack.git"));
        assertEquals(List.of("K:\\nsum_e2e\\repo"),
                NSumRepoResolver.cloneCandidates("K:\\nsum_e2e\\repo"));
    }

    @Test
    void slugifyMatchesRuntimeCacheLayout() {
        assertEquals("https___github.com_HiBer2007_HBNS-V3.git",
                NSumGit.slugify("https://github.com/HiBer2007/HBNS-V3.git"));
        assertEquals("git_github.com_HiBer2007_HBNS-V3.git",
                NSumGit.slugify("git@github.com:HiBer2007/HBNS-V3.git"));
        assertEquals("K__nsum_e2e_repo", NSumGit.slugify("K:\\nsum_e2e\\repo"));
        assertEquals(64, NSumGit.slugify("x".repeat(100)).length());
    }

    @Test
    void requestAddressFallsBackToTheWorkingAddressWithoutExistingCheckouts() {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(
                NSumRepoUrl.of(temp.resolve("missing-repository").toString()),
                NSumRepoUrl.of(source.toString()));

        assertEquals(source.toString(), NSumRepoResolver.requestAddressFor(instance, source.toString()));
    }

    private static Path createRepository(Path dir, String marker) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("workspace.json"), "{\"marker\":\"" + marker + "\"}",
                StandardCharsets.UTF_8);
        assertEquals(0, NSumGit.run(List.of("init", "-b", "main", dir.toString()), 120).exitCode());
        assertEquals(0, NSumGit.run(List.of("-C", dir.toString(), "add", "-A"), 120).exitCode());
        assertEquals(0, NSumGit.run(List.of("-C", dir.toString(),
                "-c", "user.email=nsum-test@example.com",
                "-c", "user.name=NSUM Test",
                "commit", "-m", "init"), 120).exitCode());
        return dir;
    }
}
