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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// 自检：`-Dnsum.selftest=true` 时在 HMCL 运行时里跑一遍关键路径，
/// 便于无界面回归（实例标记读写、Runtime 安装、真实同步）。
///
/// <p>可选参数：`-Dnsum.runtime.source=<NSUM 部署目录>` 指定 Runtime 来源；
/// `-Dnsum.selftest.repo=<仓库路径或 URL>` / `-Dnsum.selftest.branch=<分支>` 指定同步源。</p>
public final class NSumSelfTest {

    private static final Logger LOG = LogManager.getLogger("nsum-bridge/selftest");

    private NSumSelfTest() {
    }

    public static void runIfRequested() {
        if (!Boolean.getBoolean("nsum.selftest")) {
            return;
        }
        Thread thread = new Thread(NSumSelfTest::run, "nsum-selftest");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run() {
        int passed = 0;
        int failed = 0;
        try {
            // 1. 实例标记读写
            Path temp = Files.createTempDirectory("nsum-selftest");
            NSumInstance written = new NSumInstance();
            written.repo = System.getProperty("nsum.selftest.repo", "");
            written.branch = System.getProperty("nsum.selftest.branch", "base");
            written.gitBranch = System.getProperty("nsum.selftest.gitbranch", "");
            written.name = "selftest";
            written.write(temp);
            Optional<NSumInstance> readBack = NSumInstance.read(temp);
            boolean markerOk = readBack.isPresent()
                    && written.repo.equals(readBack.get().repo)
                    && written.branch.equals(readBack.get().branch);
            passed += markerOk ? 1 : 0;
            failed += markerOk ? 0 : 1;
            LOG.info("[NSUM-TEST] 实例标记读写: {}", markerOk ? "PASS" : "FAIL");

            // 2. Runtime 安装
            String sourceDir = System.getProperty("nsum.runtime.source", "");
            boolean runtimeOk = NSumRuntime.status().installed();
            if (!sourceDir.isBlank()) {
                try {
                    NSumRuntime.install(Path.of(sourceDir), "selftest");
                    runtimeOk = NSumRuntime.status().installed();
                } catch (Exception e) {
                    LOG.warn("[NSUM-TEST] Runtime 安装异常: {}", e.toString());
                }
            }
            passed += runtimeOk ? 1 : 0;
            failed += runtimeOk ? 0 : 1;
            LOG.info("[NSUM-TEST] Runtime 安装: {} ({})", runtimeOk ? "PASS" : "FAIL",
                    NSumRuntime.status().dir());

            // 3. 真实同步（需要 repo 与已安装 Runtime）
            boolean syncOk = false;
            if (runtimeOk && !written.repo.isBlank()) {
                NSumRuntime.SyncResult result = NSumRuntime.sync(written, temp);
                syncOk = result.success();
                LOG.info("[NSUM-TEST] 同步: {} exit={} msg={} log={}",
                        syncOk ? "PASS" : "FAIL", result.exitCode(), result.message(),
                        result.logFile());
                if (syncOk) {
                    try (var files = Files.walk(temp)) {
                        long count = files.filter(Files::isRegularFile).count();
                        LOG.info("[NSUM-TEST] 同步后实例文件数: {}", count);
                    }
                }
            } else {
                LOG.info("[NSUM-TEST] 同步: SKIP（缺 Runtime 或 -Dnsum.selftest.repo）");
            }
            if (runtimeOk && !written.repo.isBlank()) {
                passed += syncOk ? 1 : 0;
                failed += syncOk ? 0 : 1;
            }

            // 4. 元数据拉取（站点未部署/离线时 SKIP，不计失败）
            boolean metadataOk = false;
            try {
                var project = NSumMetadata.latest(NSumMetadata.TYPE_NSUM);
                metadataOk = project != null && project.latest() != null && !project.latest().isBlank();
                LOG.info("[NSUM-TEST] 元数据(NSUM.latest={}): {}",
                        project == null ? "null" : project.latest(),
                        metadataOk ? "PASS" : "SKIP");
            } catch (Throwable t) {
                LOG.info("[NSUM-TEST] 元数据: SKIP（{}）", t.toString());
            }
            if (metadataOk) {
                passed++;
            }

            // 5. 安装器三元组比对（不联网：仅验证检测逻辑）
            boolean installerOk = false;
            try {
                Path base = Files.createTempDirectory("nsum-installer");
                var repository = new org.jackhuang.hmcl.game.DefaultGameRepository(base);
                Path versionRoot = repository.getVersionRoot("nsum-test");
                Files.createDirectories(versionRoot);
                Files.writeString(versionRoot.resolve("nsum-test.json"), "{\"id\":\"nsum-test\"}");

                NSumInstance expected = new NSumInstance();
                expected.gameVersion = "1.21.5";
                expected.modloader = "fabric";
                expected.modloaderVersion = "0.16.10";

                // 无 installed* 记录 → 视为已就绪（unknown 状态）
                boolean unknownReady = NSumGameInstaller.matches(repository, "nsum-test", expected);
                // 记录为 1.21.4/forge → 与期望不一致 → 需要重装
                expected.installedGameVersion = "1.21.4";
                expected.installedModloader = "forge";
                expected.installedLoaderVersion = "47.2.0";
                boolean mismatch = NSumGameInstaller.matches(repository, "nsum-test", expected);
                installerOk = unknownReady && !mismatch;
                LOG.info("[NSUM-TEST] 安装器三元组比对: {} (unknownReady={}, mismatchDetected={})",
                        installerOk ? "PASS" : "FAIL", unknownReady, !mismatch);
            } catch (Throwable t) {
                LOG.warn("[NSUM-TEST] 安装器比对异常: {}", t.toString());
            }
            passed += installerOk ? 1 : 0;
            failed += installerOk ? 0 : 1;

            // 6. HIC CLI 分发安装（需 -Dnsum.selftest.cliDist=<nsum_cli_dist>；否则 SKIP）
            String cliDist = System.getProperty("nsum.selftest.cliDist", "");
            if (!cliDist.isBlank()) {
                boolean cliOk = false;
                try {
                    Path target = temp.resolve("runtime-cli");
                    var result = NSumHicInstaller.installFromDist(
                            Path.of(cliDist), target, (percent, detail) -> {
                                if (percent % 25 == 0) {
                                    LOG.info("[NSUM-TEST] [cli {}%] {}", percent, detail);
                                }
                            });
                    cliOk = result.success()
                            && Files.isRegularFile(target.resolve("NeoServerUpdateModpack.exe"));
                    LOG.info("[NSUM-TEST] HIC CLI 安装: {} (exit={}, msg={})",
                            cliOk ? "PASS" : "FAIL", result.exitCode(), result.message());
                } catch (Throwable t) {
                    LOG.warn("[NSUM-TEST] HIC CLI 安装异常: {}", t.toString());
                }
                passed += cliOk ? 1 : 0;
                failed += cliOk ? 0 : 1;
            } else {
                LOG.info("[NSUM-TEST] HIC CLI 安装: SKIP（未指定 nsum.selftest.cliDist）");
            }

            // 7. 可选：HMCL FileDownloadTask 真实下载（-Dnsum.selftest.download=true）
            if (Boolean.getBoolean("nsum.selftest.download")) {
                try {
                    var project = NSumMetadata.latest(NSumMetadata.TYPE_NSUM);
                    var entry = project == null ? null : project.latestEntry();
                    Path out = temp.resolve("download.zip");
                    org.jackhuang.hmcl.task.Task<Void> download =
                            new org.jackhuang.hmcl.task.FileDownloadTask(entry.downloadLink(), out)
                                    .setName("selftest download");
                    var executor = download.executor();
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    executor.addTaskListener(new org.jackhuang.hmcl.task.TaskListener() {
                        @Override
                        public void onStop(boolean success,
                                           org.jackhuang.hmcl.task.TaskExecutor taskExecutor) {
                            latch.countDown();
                        }
                    });
                    executor.start();
                    boolean done = latch.await(10, java.util.concurrent.TimeUnit.MINUTES);
                    Throwable error = executor.getException();
                    long size = Files.exists(out) ? Files.size(out) : -1;
                    LOG.info("[NSUM-TEST] HMCL 下载: {} (done={}, size={}, expect={}, error={})",
                            error == null && size > 10L * 1024 * 1024 ? "PASS" : "FAIL",
                            done, size, ">10MB", error == null ? "-" : error.toString());
                } catch (Throwable t) {
                    LOG.warn("[NSUM-TEST] HMCL 下载异常: {}", t.toString());
                }
            }
            // 8. 实例外观：图标写入 + 名称后缀
            boolean visualsOk = false;
            try {
                Path iconDir = temp.resolve("instance-visual");
                Files.createDirectories(iconDir);
                NSumInstanceVisuals.ensureIcon(iconDir);
                boolean icon = Files.isRegularFile(iconDir.resolve("icon.png"));
                String decorated = NSumInstanceVisuals.decorateName("Test Pack");
                visualsOk = icon && decorated.endsWith(NSumInstanceVisuals.NAME_SUFFIX)
                        && !decorated.endsWith(NSumInstanceVisuals.NAME_SUFFIX + NSumInstanceVisuals.NAME_SUFFIX);
                LOG.info("[NSUM-TEST] 实例外观: {} (icon={}, name={})",
                        visualsOk ? "PASS" : "FAIL", icon, decorated);
            } catch (Throwable t) {
                LOG.warn("[NSUM-TEST] 实例外观异常: {}", t.toString());
            }
            passed += visualsOk ? 1 : 0;
            failed += visualsOk ? 0 : 1;
            // 9. 端到端：导入导出包 + 真实同步（-Dnsum.selftest.e2e.zip=<zip>）
            String e2eZip = System.getProperty("nsum.selftest.e2e.zip", "");
            if (!e2eZip.isBlank()) {
                String e2eTarget = System.getProperty("nsum.selftest.e2e.target", "");
                boolean ok = NSumE2E.run(Path.of(e2eZip), e2eTarget.isBlank() ? null : Path.of(e2eTarget));
                passed += ok ? 1 : 0;
                failed += ok ? 0 : 1;
            } else {
                LOG.info("[NSUM-TEST] 端到端: SKIP（未指定 nsum.selftest.e2e.zip）");
            }
            LOG.info("[NSUM-TEST] 结果: passed={} failed={}", passed, failed);
        } catch (Throwable t) {
            LOG.error("[NSUM-TEST] 自检异常", t);
        }
    }
}
