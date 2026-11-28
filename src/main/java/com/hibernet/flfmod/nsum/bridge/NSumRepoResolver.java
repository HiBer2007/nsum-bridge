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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/// Multi-address repository handling: fallback order, reachability probing and reuse of an
/// existing checkout.
///
/// <p>The bridge never clones into a second directory to retry an address. Either the
/// Runtime creates its mirror once (for the address that passed the probe), or an existing
/// mirror is repointed with `git remote set-url` so the Runtime keeps using it.</p>
final class NSumRepoResolver {

    private static final Logger LOG = LogManager.getLogger(NSumRepoResolver.class);

    private NSumRepoResolver() {
    }

    /// Addresses of an instance in fallback order (remembered working address first).
    static List<String> candidates(NSumInstance instance) {
        if (instance == null) {
            return List.of();
        }
        return instance.orderedRepoUrls();
    }

    /// Addresses actually worth trying for a sync.
    ///
    /// <p>Order: explicit preference (URL or type) first, then the remembered working
    /// address, then the fastest reachable remaining address (all probed in parallel).
    /// Unreachable addresses stay at the end so the Runtime can still report a real error.</p>
    static List<String> attemptOrder(NSumInstance instance, Consumer<String> onLine) {
        List<String> candidates = candidates(instance);
        if (candidates.size() <= 1) {
            return candidates;
        }
        String preference = NSumRepoPreferences.preference();
        List<String> order = new ArrayList<>();
        if (isExplicit(preference)) {
            moveFirst(order, candidates, url -> NSumRepoUrls.same(url, preference));
            LOG.info("[NSUM] repository preference: explicit URL {} first", preference);
        } else if (isType(preference)) {
            moveFirst(order, candidates, url -> NSumRepoUrl.of(url).type().equals(preference));
            LOG.info("[NSUM] repository preference: {} first, others as fallback", preference);
        }
        String active = instance == null || instance.activeRepoUrl == null
                ? "" : instance.activeRepoUrl.trim();
        if (!active.isEmpty()) {
            moveFirst(order, candidates, url -> NSumRepoUrls.same(url, active));
            LOG.info("[NSUM] repository address remembered, trying it first: {}", active);
        }
        for (String candidate : candidates) {
            if (!contains(order, candidate)) {
                order.add(candidate);
            }
        }
        if (order.size() <= 1 || isExplicit(preference) || isType(preference) || !active.isEmpty()) {
            return order;
        }
        Optional<String> fastest = fastest(candidates, onLine);
        if (fastest.isEmpty()) {
            LOG.warn("[NSUM] no repository address answered the probe, keeping manifest order");
            return order;
        }
        String first = fastest.get();
        List<String> sorted = new ArrayList<>();
        sorted.add(first);
        for (String candidate : order) {
            if (!NSumRepoUrls.same(candidate, first)) {
                sorted.add(candidate);
            }
        }
        LOG.info("[NSUM] repository fallback order (fastest first): {}", sorted);
        return sorted;
    }

    private static boolean isType(String preference) {
        return "ssh".equals(preference) || "https".equals(preference) || "http".equals(preference);
    }

    private static boolean isExplicit(String preference) {
        if (preference == null || preference.isBlank() || "auto".equals(preference)
                || "manual".equals(preference) || isType(preference)) {
            return false;
        }
        return true;
    }

    private static boolean contains(List<String> urls, String value) {
        return urls.stream().anyMatch(url -> NSumRepoUrls.same(url, value));
    }

    private static void moveFirst(List<String> order, List<String> candidates,
                                  java.util.function.Predicate<String> match) {
        for (String candidate : candidates) {
            if (!contains(order, candidate) && match.test(candidate)) {
                order.add(candidate);
                return;
            }
        }
    }

    /// Probes every candidate in parallel and returns the address with the lowest latency.
    static Optional<String> fastest(List<String> candidates, Consumer<String> onLine) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (!NSumGit.available()) {
            LOG.warn("[NSUM] git is not available, repository fallback is limited to the Runtime");
            return Optional.empty();
        }
        int threads = Math.min(4, candidates.size());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.Map<String, java.util.concurrent.Future<Long>> probes = new java.util.LinkedHashMap<>();
            for (String candidate : candidates) {
                probes.put(candidate, pool.submit(
                        () -> NSumGit.probeLatency(candidate, NSumGit.PROBE_TIMEOUT_SECONDS)));
            }
            String best = null;
            long bestLatency = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, java.util.concurrent.Future<Long>> entry : probes.entrySet()) {
                long latency;
                try {
                    latency = entry.getValue().get();
                } catch (Exception e) {
                    latency = -1;
                }
                LOG.info("[NSUM] repository probe: {} -> {}", entry.getKey(),
                        latency >= 0 ? latency + "ms" : "unreachable");
                if (latency >= 0 && latency < bestLatency) {
                    best = entry.getKey();
                    bestLatency = latency;
                }
                if (latency < 0) {
                    notify(onLine, "[*] Repository address unreachable: " + entry.getKey());
                }
            }
            return Optional.ofNullable(best);
        } finally {
            pool.shutdownNow();
        }
    }

    /// First address that answers `git ls-remote`, in list order.
    static Optional<String> firstReachable(List<String> candidates, Consumer<String> onLine) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        if (!NSumGit.available()) {
            LOG.warn("[NSUM] git is not available, repository fallback is limited to the Runtime");
            return Optional.empty();
        }
        for (String candidate : candidates) {
            if (NSumGit.reachable(candidate, NSumGit.PROBE_TIMEOUT_SECONDS)) {
                return Optional.of(candidate);
            }
            LOG.warn("[NSUM] repository address unreachable, trying the next one: {}", candidate);
            notify(onLine, "[*] Repository address unreachable: " + candidate);
        }
        return Optional.empty();
    }

    /// Reuses an existing checkout for the next address: repoints its remote so the Runtime
    /// keeps working in the same directory instead of cloning a second copy.
    ///
    /// @param previousAddress address the Runtime used for the previous attempt
    /// @param nextAddress     address to fall back to
    /// @return the address the Runtime should be given again (the previous one, whose
    ///         directory was repointed) or empty when no checkout could be reused
    static Optional<String> adoptExistingCheckout(String previousAddress, String nextAddress) {
        if (previousAddress == null || previousAddress.isBlank()
                || nextAddress == null || nextAddress.isBlank()
                || NSumRepoUrls.same(previousAddress, nextAddress)) {
            return Optional.empty();
        }
        Optional<Path> checkout = NSumGit.runtimeRepoDir(previousAddress);
        if (checkout.isEmpty()) {
            return Optional.empty();
        }
        if (NSumGit.setRemoteUrl(checkout.get(), nextAddress) != 0) {
            LOG.warn("[NSUM] could not repoint {} to {}", checkout.get(), nextAddress);
            return Optional.empty();
        }
        LOG.warn("[NSUM] reusing existing checkout {} with origin {}", checkout.get(), nextAddress);
        return Optional.of(previousAddress);
    }

    /// Addresses for a manual clone: the given address plus ssh/https translations, so a
    /// blocked transport can still be cloned from the other one.
    static List<String> cloneCandidates(String url) {
        List<String> candidates = new ArrayList<>();
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return candidates;
        }
        candidates.add(value);
        for (String variant : NSumRepoUrls.schemeVariants(value)) {
            boolean duplicate = false;
            for (String candidate : candidates) {
                if (NSumRepoUrls.same(candidate, variant)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                candidates.add(variant);
            }
        }
        return candidates;
    }

    /// Outcome of a clone with fallback.
    ///
    /// @param exitCode exit code of the last attempt
    /// @param url      address of the last (or successful) attempt
    record CloneResult(int exitCode, String url) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /// Clones into `target` from the first candidate that works.
    ///
    /// <p>The same target directory is reused for every attempt - a failed attempt is cleaned
    /// up only when the bridge owns the directory (it did not exist or was empty beforehand),
    /// so an existing repository is never destroyed and a fallback never leaves a second clone
    /// behind.</p>
    static CloneResult cloneWithFallback(List<String> candidates, Path target, boolean ownedTarget) {
        int exitCode = -1;
        String lastUrl = "";
        if (candidates == null || candidates.isEmpty()) {
            return new CloneResult(exitCode, lastUrl);
        }
        for (int index = 0; index < candidates.size(); index++) {
            String candidate = candidates.get(index);
            if (index > 0) {
                LOG.warn("[NSUM] clone failed (git exit {}), trying the next address: {}",
                        exitCode, candidate);
                if (ownedTarget) {
                    NSumGit.deleteRecursively(target);
                }
            }
            exitCode = NSumGit.run(List.of("clone", candidate, target.toString()),
                    NSumRepoPreferences.cloneTimeoutSeconds()).exitCode();
            lastUrl = candidate;
            if (exitCode == 0) {
                LOG.info("[NSUM] repository cloned from {}", candidate);
                return new CloneResult(0, candidate);
            }
        }
        return new CloneResult(exitCode, lastUrl);
    }

    /// Address to hand to the Runtime for a working address: when another known address already
    /// has a checkout, that checkout is repointed and reused so no second clone is created.
    /// Returns `workingAddress` itself when nothing can be reused.
    static String requestAddressFor(NSumInstance instance, String workingAddress) {
        String working = workingAddress == null ? "" : workingAddress.trim();
        if (working.isEmpty()) {
            return working;
        }
        for (String candidate : candidates(instance)) {
            if (NSumRepoUrls.same(candidate, working)) {
                continue;
            }
            Optional<String> reuse = adoptExistingCheckout(candidate, working);
            if (reuse.isPresent()) {
                return reuse.get();
            }
        }
        return working;
    }

    /// Feeds a fallback notice to the launch progress (also logged by the caller).
    static void notify(Consumer<String> onLine, String message) {
        if (onLine == null || message == null || message.isBlank()) {
            return;
        }
        try {
            onLine.accept(message);
        } catch (Throwable ignored) {
        }
    }
}
