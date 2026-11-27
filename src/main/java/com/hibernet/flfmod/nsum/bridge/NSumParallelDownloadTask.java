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
import org.jackhuang.hmcl.task.Task;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;

/// 下载 NSUM Runtime 包：优先多线程分片（参考 FLF `hmcl-parallel-download`），
/// 不适用/失败时回退单流；可选 SHA-256 校验。
final class NSumParallelDownloadTask extends Task<Void> {

    private static final Logger LOG = LogManager.getLogger(NSumParallelDownloadTask.class);

    private final URI uri;
    private final Path target;
    private final String sha256;
    private final String displayName;

    NSumParallelDownloadTask(String link, Path target, String sha256, String displayName) {
        this.uri = URI.create(link);
        this.target = target;
        this.sha256 = sha256 == null ? "" : sha256;
        this.displayName = displayName;
        setName("下载 " + displayName);
    }

    @Override
    public void execute() throws Exception {
        NSumParallelDownloader.Options options =
                NSumParallelDownloader.loadOptions(
                        NSumPaths.nsumDir().resolve("download.properties"));
        if (Boolean.parseBoolean(System.getProperty("nsum.download.enabled", "true")) == false) {
            options = new NSumParallelDownloader.Options(1, Long.MAX_VALUE, 0, 3,
                    options.connectTimeoutMs(), options.readTimeoutMs(),
                    options.maxPerServer(), options.maxDurationMs());
        }

        setStage("下载 " + displayName + "（多线程 " + options.threads() + "）");
        NSumParallelDownloader.Result result = NSumParallelDownloader.download(
                uri, target, options,
                (received, total) -> updateProgress(received, total),
                this::isCancelled);

        if (result.cancelled() || isCancelled()) {
            throw new CancellationException("下载已取消");
        }
        if (!result.success()) {
            LOG.info("[NSUM] 多线程下载不可用（{}），回退单流下载", result.message());
            setStage("下载 " + displayName + "（单流）");
            NSumParallelDownloader.downloadSingle(uri, target, options,
                    (received, total) -> updateProgress(received, total),
                    this::isCancelled);
        } else {
            LOG.info("[NSUM] 多线程下载完成：{} 线程，{} bytes",
                    result.threadsUsed(), result.bytes());
        }

        if (!sha256.isBlank()) {
            String actual = sha256(target);
            if (!actual.equalsIgnoreCase(sha256)) {
                Files.deleteIfExists(target);
                throw new IOException("SHA-256 校验失败（期望 " + sha256
                        + "，实际 " + actual + "）");
            }
        }
        updateProgress(1.0);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
