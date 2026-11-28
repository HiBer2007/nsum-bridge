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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/// Small git wrapper used by the repository fallback: probing addresses, reading and
/// repointing remotes, and locating the Runtime's repository cache.
///
/// <p>Everything here is best effort: a missing git binary, unknown cache layout or
/// unexpected output degrades to "no information" so that sync falls back to the plain
/// single-address behaviour instead of failing.</p>
final class NSumGit {

    private static final Logger LOG = LogManager.getLogger(NSumGit.class);

    /// Upper bound for `ls-remote` probes (seconds).
    static final int PROBE_TIMEOUT_SECONDS = NSumRepoPreferences.probeTimeoutSeconds();
    /// Upper bound for local git commands (seconds).
    static final int COMMAND_TIMEOUT_SECONDS = Integer.getInteger("nsum.repo.commandTimeoutSeconds", 120);

    /// Runtime application data folder (`%LOCALAPPDATA%/NeoServerUpdateModpack`).
    private static final String RUNTIME_APP_DIR = "NeoServerUpdateModpack";

    private NSumGit() {
    }

    /// Result of one git invocation.
    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /// Git executable: the Runtime's bundled MinGit when installed, otherwise `git` on PATH.
    static String executable() {
        Path dir = NSumPaths.runtimeDir().resolve("tools").resolve("git");
        for (String relative : List.of("cmd/git.exe", "bin/git.exe", "mingw64/bin/git.exe", "ucrt64/bin/git.exe")) {
            Path candidate = dir.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return "git";
    }

    /// True when the git executable answers `--version`.
    static boolean available() {
        return run(List.of("--version"), 20).ok();
    }

    /// Runs git with the given arguments; stdin is discarded and the output is captured
    /// (merged stderr) so a credential prompt can never block the bridge.
    static Result run(List<String> arguments, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(executable());
        command.addAll(arguments);
        return runCommand(command, timeoutSeconds);
    }

    /// Runs an arbitrary command; see [run].
    static Result runCommand(List<String> command, int timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.PIPE);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            builder.environment().put("GCM_INTERACTIVE", "never");
            process = builder.start();
            Process started = process;
            // Close stdin immediately: git then sees EOF instead of waiting for input.
            try {
                started.getOutputStream().close();
            } catch (IOException ignored) {
            }
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> readAll(started.getInputStream()));
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                LOG.warn("[NSUM] git command timed out after {}s: {}", timeoutSeconds, String.join(" ", command));
                return new Result(-1, "timeout after " + timeoutSeconds + "s");
            }
            String text = output.get(5, TimeUnit.SECONDS);
            return new Result(process.exitValue(), text == null ? "" : text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroy(process);
            return new Result(-1, "interrupted");
        } catch (Exception e) {
            destroy(process);
            LOG.debug("[NSUM] git command failed: {} ({})", String.join(" ", command), e.toString());
            return new Result(-1, e.toString());
        }
    }

    /// True when the address answers `git ls-remote` (works for URLs and local paths).
    ///
    /// <p>No ref pattern is passed on purpose: a reachable but still empty repository lists no
    /// refs and must not be mistaken for an unreachable address.</p>
    static boolean reachable(String url, int timeoutSeconds) {
        return probeLatency(url, timeoutSeconds) >= 0;
    }

    /// Latency of `git ls-remote` in milliseconds, or `-1` when unreachable/timeout.
    static long probeLatency(String url, int timeoutSeconds) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return -1;
        }
        long started = System.nanoTime();
        Result result = run(List.of("ls-remote", value), timeoutSeconds);
        if (!result.ok()) {
            return -1;
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    /// True when `dir` is the root of a git working tree.
    static boolean isRepository(Path dir) {
        return dir != null && Files.isDirectory(dir) && Files.exists(dir.resolve(".git"));
    }

    /// `remote.origin.url` of a working tree (empty when unset or not a repository).
    static String remoteUrl(Path repo) {
        if (!isRepository(repo)) {
            return "";
        }
        Result result = run(List.of("-C", repo.toString(), "config", "--get", "remote.origin.url"), 20);
        return result.ok() ? result.output().trim() : "";
    }

    /// Points `origin` of an existing working tree at `url`; returns the git exit code.
    static int setRemoteUrl(Path repo, String url) {
        if (!isRepository(repo) || url == null || url.isBlank()) {
            return -1;
        }
        String current = remoteUrl(repo);
        if (NSumRepoUrls.same(current, url)) {
            return 0;
        }
        if (current.isEmpty()) {
            return run(List.of("-C", repo.toString(), "remote", "add", "origin", url), 60).exitCode();
        }
        return run(List.of("-C", repo.toString(), "remote", "set-url", "origin", url), 60).exitCode();
    }

    /// Directory the Runtime uses for its mirror of a repository URL
    /// (`<LOCALAPPDATA>/NeoServerUpdateModpack/cache/repos/<slug>`), when it exists and is a
    /// git working tree. Mirrors the Runtime cache layout; a layout change only disables
    /// checkout reuse, never sync itself.
    static Optional<Path> runtimeRepoDir(String url) {
        String value = url == null ? "" : url.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return runtimeCacheReposDir()
                .map(dir -> dir.resolve(slugify(value)))
                .filter(NSumGit::isRepository);
    }

    /// `%LOCALAPPDATA%/NeoServerUpdateModpack/cache/repos` (empty when undeterminable).
    static Optional<Path> runtimeCacheReposDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            String home = System.getProperty("user.home", "");
            if (home.isBlank()) {
                return Optional.empty();
            }
            base = Paths.get(home, "AppData", "Local").toString();
        }
        return Optional.of(Paths.get(base, RUNTIME_APP_DIR, "cache", "repos"));
    }

    /// Same slug rule as the Runtime: every character outside `[A-Za-z0-9._-]` becomes `_`,
    /// then the name is truncated to 64 characters.
    static String slugify(String url) {
        String value = url == null ? "" : url.trim();
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            builder.append(keep ? c : '_');
        }
        return builder.length() > 64 ? builder.substring(0, 64) : builder.toString();
    }

    /// Recursively deletes a directory (no-op when absent).
    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.debug("[NSUM] failed to delete {}: {}", path, e.toString());
                }
            });
        } catch (IOException e) {
            LOG.warn("[NSUM] failed to delete {}: {}", dir, e.toString());
        }
    }

    /// True when the directory has no entries (or does not exist).
    static boolean isEmptyDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return !Files.exists(dir);
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /// One-line description of a git failure for warnings and dialogs.
    static String brief(Result result) {
        String text = result == null || result.output() == null ? "" : result.output().replace("\u001b", "").trim();
        if (text.isEmpty()) {
            return "git exit " + (result == null ? -1 : result.exitCode());
        }
        int newline = text.indexOf('\n');
        String line = newline < 0 ? text : text.substring(0, newline);
        line = line.replace('\r', ' ');
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    private static String readAll(InputStream stream) {
        try (InputStream input = stream) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = input.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            return text.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static void destroy(Process process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
