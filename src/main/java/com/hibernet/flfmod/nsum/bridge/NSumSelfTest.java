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
            if (!runtimeOk && !sourceDir.isBlank()) {
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
            passed += syncOk ? 1 : 0;
            failed += syncOk ? 0 : 1;

            // 4. 版本描述生成（伪造一个已安装的 Fabric 版本作为继承基）
            boolean descriptorOk = false;
            try {
                Path versionsRoot = temp.resolve("versions");
                Path baseDir = versionsRoot.resolve("fabric-loader-0.16.10-1.21.4");
                Files.createDirectories(baseDir);
                String baseJson = "{\"id\":\"fabric-loader-0.16.10-1.21.4\","
                        + "\"inheritsFrom\":\"1.21.4\","
                        + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\","
                        + "\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.16.10\"}]}";
                Files.writeString(baseDir.resolve("fabric-loader-0.16.10-1.21.4.json"), baseJson);

                Path instDir = versionsRoot.resolve("nsum-selftest-inst");
                Files.createDirectories(instDir);
                NSumInstance descriptorInstance = new NSumInstance();
                descriptorInstance.name = "nsum-selftest-inst";
                descriptorInstance.gameVersion = "1.21.4";
                descriptorInstance.modloaderVersion = "0.16.10";
                descriptorOk = NSumVersionDescriptor.ensure(
                        versionsRoot, instDir, descriptorInstance.name, descriptorInstance)
                        && Files.isRegularFile(instDir.resolve(descriptorInstance.name + ".json"));
                LOG.info("[NSUM-TEST] 版本描述生成: {} ({})",
                        descriptorOk ? "PASS" : "FAIL", instDir.resolve(descriptorInstance.name + ".json"));
            } catch (Throwable t) {
                LOG.warn("[NSUM-TEST] 版本描述生成异常: {}", t.toString());
            }
            passed += descriptorOk ? 1 : 0;
            failed += descriptorOk ? 0 : 1;

            LOG.info("[NSUM-TEST] 结果: passed={} failed={}", passed, failed);
        } catch (Throwable t) {
            LOG.error("[NSUM-TEST] 自检异常", t);
        }
    }
}
