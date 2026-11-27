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

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/// HTTP Range 分片多线程下载器。
///
/// <p>逻辑参考 FLF 的 `hmcl-parallel-download`：探测 206/Content-Range →
/// 切片 → N 线程 Range GET 写入预分配文件（FileChannel 定位写）→ 进度聚合 →
/// 可选 SHA-256 校验；服务器不支持 Range、文件过小或长度未知时由调用方回退
/// 单流下载。</p>
final class NSumParallelDownloader {

    private static final Logger LOG = LogManager.getLogger(NSumParallelDownloader.class);

    record Options(int threads, long minSize, long chunkSize, int retries,
                   int connectTimeoutMs, int readTimeoutMs, int maxPerServer,
                   long maxDurationMs) {
        static Options defaults() {
            return new Options(16, 8L * 1024 * 1024, 0L, 2, 10000, 30000, 32,
                    10 * 60 * 1000L);
        }
    }

    record Result(boolean success, boolean ranged, boolean cancelled,
                  long bytes, int threadsUsed, String message) {
        static Result fallback(String reason) {
            return new Result(false, false, false, 0, 0, reason);
        }
    }

    interface Progress {
        void update(long received, long total);
    }

    private static final ConcurrentHashMap<String, Semaphore> SERVER_LIMITERS =
            new ConcurrentHashMap<>();

    private static Semaphore limiter(URI uri, int maxPerServer) {
        String key = uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort();
        return SERVER_LIMITERS.computeIfAbsent(key,
                ignored -> new Semaphore(Math.max(1, maxPerServer)));
    }

    private NSumParallelDownloader() {
    }

    /// 读取配置：系统属性 > `<nsum>/download.properties` > 默认值。
    static Options loadOptions(Path configFile) {
        Options defaults = Options.defaults();
        int threads = defaults.threads();
        long minSize = defaults.minSize();
        long chunk = defaults.chunkSize();
        int retries = defaults.retries();

        Properties properties = new Properties();
        if (configFile != null && Files.isRegularFile(configFile)) {
            try (InputStream in = Files.newInputStream(configFile)) {
                properties.load(in);
            } catch (IOException e) {
                LOG.debug("[NSUM] 读取下载配置失败: {}", e.toString());
            }
        }
        threads = intValue(properties, "threads", threads);
        minSize = mbValue(properties, "minSizeMB", minSize / (1024 * 1024)) * 1024 * 1024;
        chunk = mbValue(properties, "chunkMB", chunk / (1024 * 1024)) * 1024 * 1024;
        retries = intValue(properties, "retries", retries);

        threads = Integer.getInteger("nsum.download.threads", threads);
        minSize = Long.getLong("nsum.download.minSizeMB", minSize / (1024 * 1024)) * 1024 * 1024;
        chunk = Long.getLong("nsum.download.chunkMB", chunk / (1024 * 1024)) * 1024 * 1024;
        retries = Integer.getInteger("nsum.download.retries", retries);

        threads = Math.max(1, Math.min(threads, 128));
        return new Options(threads, Math.max(0, minSize), Math.max(0, chunk),
                Math.max(1, retries), defaults.connectTimeoutMs(),
                defaults.readTimeoutMs(), defaults.maxPerServer(),
                defaults.maxDurationMs());
    }

    private static int intValue(Properties properties, String key, int fallback) {
        try {
            String value = properties.getProperty(key);
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long mbValue(Properties properties, String key, long fallback) {
        try {
            String value = properties.getProperty(key);
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Result download(URI uri, Path target, Options options,
                           Progress progress, BooleanSupplier cancelled) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
        } catch (IOException e) {
            return new Result(false, false, false, 0, 0, "无法创建下载目录: " + e.getMessage());
        }

        Probe probe = probe(uri, options);
        if (probe == null) {
            return Result.fallback("无法探测远程文件大小（回退单流）");
        }
        long total = probe.length();
        int wanted = options.threads();
        if (!probe.ranged() || total < options.minSize() || total <= 0) {
            return Result.fallback(probe.ranged()
                    ? "文件小于多线程阈值（" + (total / (1024 * 1024)) + " MB）"
                    : "服务器不支持 Range");
        }

        long chunk = options.chunkSize() > 0 ? options.chunkSize()
                : Math.max(1024L * 1024,
                        Math.min(4L * 1024 * 1024, total / Math.max(1, wanted * 16L)));
        chunk = Math.max(256L * 1024, Math.min(chunk, total));
        int segments = (int) Math.min(wanted, Math.max(1, (total + chunk - 1) / chunk));

        LOG.info("[NSUM] 多线程下载 {} 线程，总 {} bytes，分片 {} bytes",
                segments, total, chunk);
        long bytes = 0;
        int threadsUsed = 0;
        try {
            FileChannel file = FileChannel.open(target,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                file.truncate(total);
                AtomicLong received = new AtomicLong();
                AtomicInteger active = new AtomicInteger();
                AtomicBoolean cancelledFlag = new AtomicBoolean();
                long[] lastReport = {0L};
                java.util.function.LongConsumer aggregated = delta -> {
                    long now = received.addAndGet(delta);
                    if (progress == null) {
                        return;
                    }
                    long last = lastReport[0];
                    if (now - last >= 256 * 1024 || now >= total) {
                        lastReport[0] = now;
                        progress.update(now, total);
                    }
                };
                AtomicBoolean failed = new AtomicBoolean();
                String[] failure = new String[1];

                ExecutorService pool = Executors.newFixedThreadPool(segments);
                final long deadline = System.nanoTime() + options.maxDurationMs() * 1_000_000L;
                try {
                    for (int i = 0; i < segments; i++) {
                        long start = (long) i * chunk;
                        long end = (i == segments - 1) ? total - 1 : start + chunk - 1;
                        final int index = i;
                        pool.submit(() -> {
                            if (failed.get()) {
                                return;
                            }
                            String error = downloadSegment(uri, file, total, start, end,
                                    options, aggregated, cancelled,
                                    () -> cancelledFlag.get() || failed.get());
                            if (error != null) {
                                synchronized (failure) {
                                    if (!failed.getAndSet(true)) {
                                        failure[0] = "分片 " + index + ": " + error;
                                    }
                                }
                                LOG.warn("[NSUM] 分片 {} 失败: {}", index, error);
                                pool.shutdownNow();
                            }
                        });
                        active.incrementAndGet();
                    }
                    pool.shutdown();
                    // 取消/超时/任一分片失败时中断其余分片，尽快回退单流。
                    while (!pool.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                        if (cancelled.getAsBoolean()) {
                            cancelledFlag.set(true);
                            pool.shutdownNow();
                        } else if (failed.get()) {
                            pool.shutdownNow();
                        } else if (System.nanoTime() > deadline) {
                            synchronized (failure) {
                                if (!failed.getAndSet(true)) {
                                    failure[0] = "下载超时（超过 "
                                            + (options.maxDurationMs() / 1000) + " 秒）";
                                }
                            }
                            pool.shutdownNow();
                        }
                    }
                } finally {
                    if (!pool.isTerminated()) {
                        pool.shutdownNow();
                    }
                }

                if (cancelledFlag.get() || cancelled.getAsBoolean()) {
                    closeQuietly(file);
                    Files.deleteIfExists(target);
                    return new Result(false, true, true, 0, segments, "下载已取消");
                }
                if (failed.get()) {
                    closeQuietly(file);
                    Files.deleteIfExists(target);
                    return new Result(false, true, false, 0, segments, failure[0]);
                }
                if (file.size() != total) {
                    long size = file.size();
                    closeQuietly(file);
                    Files.deleteIfExists(target);
                    return new Result(false, true, false, 0, segments,
                            "文件大小不符: " + size + " / " + total);
                }
                bytes = total;
                threadsUsed = segments;
            } finally {
                closeQuietly(file);
            }
            if (progress != null) {
                progress.update(total, total);
            }
            return new Result(true, true, false, bytes, threadsUsed, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
            }
            return new Result(false, true, true, 0, segments, "下载已取消");
        } catch (IOException e) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
            }
            return new Result(false, false, false, 0, 0, "下载失败: " + e.getMessage());
        }
    }

    private static String downloadSegment(URI uri, FileChannel file, long total,
                                          long start, long end, Options options,
                                          java.util.function.LongConsumer progress,
                                          BooleanSupplier cancelled,
                                          BooleanSupplier abort) {
        long position = start;
        int attempts = 0;
        while (position <= end && attempts <= options.retries()) {
            if (abort.getAsBoolean()) {
                return "已中止";
            }
            attempts++;
            HttpURLConnection connection = null;
            Semaphore semaphore = limiter(uri, options.maxPerServer());
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "已取消";
            }
            try {
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(options.connectTimeoutMs());
                connection.setReadTimeout(options.readTimeoutMs());
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "NSUM-Bridge/1.0");
                connection.setRequestProperty("Accept", "*/*");
                connection.setRequestProperty("Range",
                        "bytes=" + position + "-" + end);
                int status = connection.getResponseCode();
                if (status != 206) {
                    return status == 200
                            ? "服务器忽略了 Range 请求"
                            : "HTTP " + status;
                }
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        if (abort.getAsBoolean()) {
                            return "已中止";
                        }
                        ByteBuffer data = ByteBuffer.wrap(buffer, 0, read);
                        long written = 0;
                        while (data.hasRemaining()) {
                            int n = file.write(data, position + written);
                            if (n <= 0) {
                                throw new IOException("写入失败");
                            }
                            written += n;
                        }
                        position += read;
                        progress.accept(read);
                    }
                }
                if (position <= end) {
                    // 提前 EOF：重试剩余区间
                    continue;
                }
                return null;
            } catch (IOException e) {
                if (attempts > options.retries()) {
                    return e.getMessage() == null ? "网络错误" : e.getMessage();
                }
                try {
                    Thread.sleep(300L * attempts);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "已取消";
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                semaphore.release();
            }
        }
        return position > end ? null : "重试次数用尽";
    }

    private record Probe(long length, boolean ranged) {
    }

    private static Probe probe(URI uri, Options options) {
        Probe head = probeWith(uri, options, "HEAD");
        if (head != null && head.length() > 0) {
            return head;
        }
        return probeWith(uri, options, "GET");
    }

    private static Probe probeWith(URI uri, Options options, String method) {
        HttpURLConnection connection = null;
        Semaphore semaphore = limiter(uri, options.maxPerServer());
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(options.connectTimeoutMs());
            connection.setReadTimeout(options.readTimeoutMs());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "NSUM-Bridge/1.0");
            if ("GET".equals(method)) {
                connection.setRequestProperty("Range", "bytes=0-0");
            }
            int status = connection.getResponseCode();
            String contentRange = connection.getHeaderField("Content-Range");
            long length = -1;
            boolean ranged = false;
            if (contentRange != null && contentRange.contains("/")) {
                try {
                    length = Long.parseLong(contentRange.substring(
                            contentRange.lastIndexOf('/') + 1).trim());
                    ranged = status == 206 && length > 0;
                } catch (NumberFormatException ignored) {
                }
            }
            if (length <= 0) {
                String value = connection.getHeaderField("Content-Length");
                if (value != null) {
                    try {
                        length = Long.parseLong(value.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                String accept = connection.getHeaderField("Accept-Ranges");
                ranged = length > 0 && accept != null
                        && accept.toLowerCase().contains("bytes");
            }
            // 消费 GET 探针的 1 字节响应，避免连接悬挂
            if ("GET".equals(method) && connection.getInputStream() != null) {
                try (InputStream ignored = connection.getInputStream()) {
                    // ignore
                }
            }
            if (length > 0) {
                return new Probe(length, ranged);
            }
            return null;
        } catch (IOException e) {
            LOG.debug("[NSUM] {} 探测失败: {}", method, e.toString());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            semaphore.release();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
        }
    }

    /// 单流兜底下载（多线程不适用或失败时使用）。
    static void downloadSingle(URI uri, Path target, Options options,
                               Progress progress, BooleanSupplier cancelled) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= options.retries(); attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new java.util.concurrent.CancellationException("下载已取消");
            }
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) uri.toURL().openConnection();
                connection.setConnectTimeout(options.connectTimeoutMs());
                connection.setReadTimeout(options.readTimeoutMs());
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "NSUM-Bridge/1.0");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status);
                }
                long total = connection.getContentLengthLong();
                try (InputStream in = connection.getInputStream();
                     var out = Files.newOutputStream(target,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    long received = 0;
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        if (cancelled.getAsBoolean()) {
                            throw new java.util.concurrent.CancellationException("下载已取消");
                        }
                        out.write(buffer, 0, read);
                        received += read;
                        if (progress != null) {
                            progress.update(received, total);
                        }
                    }
                }
                return;
            } catch (IOException e) {
                last = e;
                LOG.warn("[NSUM] 单流下载失败（第 {} 次）: {}", attempt, e.toString());
                try {
                    Thread.sleep(500L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("下载已取消");
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw last == null ? new IOException("下载失败") : last;
    }
}
