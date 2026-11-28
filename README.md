# NSUM Bridge — HMCL ↔ NSUM 客户端桥接模组

给 HMCL 增加 **NSUM 整合包类型**、**NSUM Runtime 启动方式**和 **NSUM 管理页面**。
依赖 [FLFHMCL](https://github.com/HiBer2007/FLFHMCL)（把 Fabric 运行时挂到 HMCL 上）与 FLF API 的扩展点。**本仓库独立**，不再是 FLFHMCL 的子目录；编译期依赖其工作区的 `testbed/libraries` 与 `mods/*/build/libs`。

**开源协议：LGPL-2.1-only**

## 它做什么

| 能力 | 注册点 | 名字 |
|------|--------|------|
| 整合包类型 | `ModpackTypes.register(...)` | `nsum` |
| 启动方式 | `LaunchStrategies.register(...)` | `nsum-runtime` |
| GUI | `FLFHMCLUIApi` | 侧栏分类 `nsum` / 页面 `nsum-bridge:home` |

- 导入 `.nsumpack`（NSUM 清单型整合包）→ 建实例 + 写 `.NSUM/instance.json`；默认包不含文件，附带文件按清单 `embedded[]` 安装并缓存到 `.hmcl/nsum/cache/files/`；
- 清单 `repository.urls[]` 的多个仓库地址按顺序回退（见下文「多仓库地址回退」）；
- 启动 NSUM 实例时先让 **NSUM Runtime** 同步仓库内容，成功后再交回 HMCL 正常启动；
- Runtime 由本模组自行安装到 `<HMCL>/.hmcl/nsum/runtime/`（自动下载后续再做）；
- **版本描述 `<id>.json` 由本模组生成**（`NSumVersionDescriptor`）：优先继承已安装的 Fabric 版本并复制其 mainClass/arguments/libraries，缺失时写最小描述并告警；安装与每次启动前幂等补齐。

**与 NSUM 的边界**：bridge 与 NSUM **只通过 CLI 通讯**（`exec build --format hmcl`），所有界面都是 HMCL 原生 JavaFX 自绘，不调用 NSUM 的 Qt GUI。

## 目录约定

```
<HMCL 工作目录>/.hmcl/nsum/
  ├── config.json                # runtimeSourceDir / launchOnSyncFailure / lastRuntimeVersion
  ├── runtime/                   # NSUM 部署副本（NeoServerUpdateModpack.exe + 依赖）
  │     └── .nsum-runtime.json   # 安装标记 {version, installedAt, source}
  └── logs/sync-*.log            # 每次同步的完整输出

<实例目录>/.NSUM/instance.json   # {schema, repo, branch, gitBranch, name, minRuntimeVersion, lastSync}
```

## 构建

编译期依赖全部取自本地（不联网）：

```powershell
# 1. 先构建 FLFHMCL 工作区（提供 mods/*/build/libs 下的 FLF API jar）
cd K:\FLFHMCL
.\gradlew.bat buildAll

# 2. 构建本模组
cd K:
sum-bridge
.\gradlew.bat build
# 产物：build/libs/nsum-bridge-0.1.0.jar
```

可覆盖的 `-P` 参数：`flfLibrariesDir`（默认 `<工作区>/testbed/libraries`）、`flfApiDir`（默认 `<工作区>/mods`）、`hmclVersion`、`hmclJar`。

> 工作区构建需要 `testbed/HMCL-<version>.jar`；若只有 `testbed/runtime/hmcl/HMCL-<version>.jar`，先复制过去。

## 使用

1. 把 `nsum-bridge-0.1.0.jar` 放进 HMCL 的 `mods/`（与 FLF API 的 `flf-api*` jar 一起）；
2. 启动 HMCL → 侧栏出现「NSUM」→ 打开页面；
3. 点「安装 / 更新 Runtime」：从 `runtimeSourceDir`（`.hmcl/nsum/config.json`）或 `<HMCL>/nsum-runtime`、`<HMCL>/deploy` 找 NSUM 部署目录并复制；
4. 导入 NSUM 整合包（`nsum.modpack.json` 清单）→ 实例创建；
5. 启动实例：先同步（日志见 `.hmcl/nsum/logs/`），成功后进入游戏。

## 多线程下载（Runtime / HIC 依赖）

下载 NSUM Runtime 包与 HIC 声明的外部依赖时使用 HTTP Range 分片多线程（逻辑参考 FLF 的 `hmcl-parallel-download`）：

- 自动探测 `206 / Content-Range`；支持 Range 且体积 ≥ 8 MB 时按分片并发写入预分配文件，默认 16 线程（上限 32/服务器）；
- 服务器不支持 Range、体积过小、超时（默认 10 分钟）或任一分片失败 → 自动回退 HMCL/单流下载；
- 下载完成后按元数据 `sha_256` 校验；进度与取消沿用 HMCL 任务对话框；
- 配置：`<HMCL>/.hmcl/nsum/download.properties`：

```properties
enabled=true
threads=16
minSizeMB=8
chunkMB=0
retries=2
```

  也可用系统属性覆盖：`-Dnsum.download.threads=8`、`-Dnsum.download.minSizeMB=8`、`-Dnsum.download.enabled=false`。


## 多仓库地址回退

NSUM 导出的 `nsum.index.json` 里 `repository.urls[]` 是 `[{type: "ssh"|"https"|"http", url: "..."}]`，`repository.url` 仍是旧版主地址。bridge 按下面的顺序取地址：

1. `repository.urls[]`（保持清单顺序与 type）；
2. 数组缺失或为空 → `repository.url`；
3. 仍为空 → 旧包的 `instance.repo`。

地址会去重（忽略尾部 `/` 与大小写），`NSumInstance.repo()` 返回列表第一个地址，`orderedRepoUrls()` 返回完整回退顺序。行为：

- 有多个地址且还没有「上次可用地址」时，**并行** `git ls-remote` 探测所有地址，延迟最低的排到最前（自动最快，自 0.4.6.17）；
- 每个地址有独立超时：探测 8s、同步 300s、手动克隆 180s（可通过 `.hmcl/nsum/repo.properties` 或系统属性调整）；超时/异常/Runtime 非 0 退出只记 warning，HUD 显示 `Repository fallback: …`，随后换下一个地址；全部失败才把**第一个**错误报给用户；
- 手动指定：`.hmcl/nsum/repo.properties` 的 `preference=auto|ssh|https|http|manual|<URL>`（`auto` 为默认；`ssh/https/http` 优先该协议，`manual` 保持清单顺序，显式 URL 最优先），或系统属性 `-Dnsum.repo.preference=...`。
- 已有仓库不回退重克隆：定位 `<LOCALAPPDATA>/NeoServerUpdateModpack/cache/repos/<slug>` 下的 Runtime 检出目录，`git remote set-url` 指向新地址后继续用同一个目录；
- 成功后把实际使用的地址写入实例标记 `activeRepoUrl`，下次启动直接用它（不再探测）；
- 手动「克隆 NSUM 仓库」失败时会对 `https↔ssh` 互转地址再试一次，目标目录始终只有一个，失败残留清理后再试。

清单解析在 `NSumRepoUrls`（纯 JSON，无 HMCL 依赖），探测与 remote 操作在 `NSumGit` / `NSumRepoResolver`，相关单元测试见 `src/test/java`（`.\gradlew.bat test`）。

## 自检（无界面）

```powershell
java -javaagent:...FLFHMCL.jar ... -Dnsum.selftest=true `
     -Dnsum.runtime.source=<NSUM deploy> `
     -Dnsum.selftest.repo=<仓库路径> -Dnsum.selftest.branch=base -Dnsum.selftest.gitbranch=main
```

日志中应出现 `[NSUM-TEST]` 三项 `PASS`（实例标记读写、Runtime 安装、真实同步）。
