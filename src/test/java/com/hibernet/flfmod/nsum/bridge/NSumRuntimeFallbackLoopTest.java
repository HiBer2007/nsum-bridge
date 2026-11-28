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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Sync fallback loop: address order, warning/retry, first-error reporting and cancellation.
class NSumRuntimeFallbackLoopTest {

    @TempDir
    Path temp;

    private String primary;
    private String fallback;
    private Path logFile;

    @BeforeEach
    void prepare() {
        primary = temp.resolve("primary-repo").toString();
        fallback = temp.resolve("fallback-repo").toString();
        logFile = temp.resolve("sync.log");
    }

    @Test
    void usesSingleAttemptWhenPrimaryWorks() throws Exception {
        List<String> attempted = new ArrayList<>();

        NSumRuntime.SyncResult result = NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> {
                    attempted.add(address);
                    return success("ok");
                });

        assertTrue(result.success());
        assertEquals(List.of(primary), attempted);
    }

    @Test
    void fallsBackWhenPrimaryReturnsNonZeroExit() throws Exception {
        List<String> attempted = new ArrayList<>();

        NSumRuntime.SyncResult result = NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> {
                    attempted.add(address);
                    return attempted.size() == 1
                            ? new NSumRuntime.Attempt(1, "fatal: unable to access", false, "Runtime 退出码 1")
                            : success("cloned");
                });

        assertTrue(result.success());
        assertEquals(List.of(primary, fallback), attempted);
        NSumInstance written = NSumInstance.read(temp).orElseThrow();
        assertEquals(fallback, written.activeRepoUrl);
        assertFalse(written.lastSync.isBlank());
        String log = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(log.contains("attempt 1"), "log should record the failed attempt");
        assertTrue(log.contains("attempt 2"), "log should record the fallback attempt");
    }

    @Test
    void treatsExceptionAsFailureAndKeepsTrying() {
        List<String> attempted = new ArrayList<>();

        NSumRuntime.SyncResult result = NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> {
                    attempted.add(address);
                    if (attempted.size() == 1) {
                        throw new IllegalStateException("runtime missing");
                    }
                    return success("cloned");
                });

        assertTrue(result.success());
        assertEquals(List.of(primary, fallback), attempted);
    }

    @Test
    void reportsFirstErrorWhenEveryAddressFails() {
        List<String> attempted = new ArrayList<>();

        NSumRuntime.SyncResult result = NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> {
                    attempted.add(address);
                    boolean first = attempted.size() == 1;
                    return new NSumRuntime.Attempt(first ? 1 : 2, "", false,
                            first ? "Runtime 退出码 1" : "Runtime 退出码 2");
                });

        assertFalse(result.success());
        assertEquals(2, attempted.size());
        // The original (first) error is what the caller sees.
        assertEquals(1, result.exitCode());
        assertEquals("Runtime 退出码 1", result.message());
    }

    @Test
    void cancellationStopsTheFallback() {
        List<String> attempted = new ArrayList<>();

        NSumRuntime.SyncResult result = NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> {
                    attempted.add(address);
                    return new NSumRuntime.Attempt(-2, "cancelled", true, "同步已取消");
                });

        assertFalse(result.success());
        assertEquals(-2, result.exitCode());
        assertEquals(1, attempted.size(), "a cancelled run must not try another address");
    }

    @Test
    void remembersWorkingAddressForTheNextRun() {
        NSumRuntime.syncWithFallback(instance(), temp, logFile, null,
                address -> NSumRepoUrls.same(address, primary)
                        ? new NSumRuntime.Attempt(1, "", false, "Runtime 退出码 1")
                        : success("ok"));

        NSumInstance written = NSumInstance.read(temp).orElseThrow();
        assertEquals(fallback, written.activeRepoUrl);
        // The remembered address is probed first on the next run.
        assertEquals(fallback, written.orderedRepoUrls().get(0));
    }

    private NSumInstance instance() {
        NSumInstance instance = new NSumInstance();
        instance.repoUrls = List.of(NSumRepoUrl.of(primary), NSumRepoUrl.of(fallback));
        instance.branch = "base";
        return instance;
    }

    private static NSumRuntime.Attempt success(String output) {
        return new NSumRuntime.Attempt(0, output, false, "同步成功");
    }
}
