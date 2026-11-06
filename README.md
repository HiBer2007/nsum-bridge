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

- 导入 `*_nsum.zip`（NSUM 导出的整合包）→ 建实例 + 写 `.NSUM/instance.json`；
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

## 自检（无界面）

```powershell
java -javaagent:...FLFHMCL.jar ... -Dnsum.selftest=true `
     -Dnsum.runtime.source=<NSUM deploy> `
     -Dnsum.selftest.repo=<仓库路径> -Dnsum.selftest.branch=base -Dnsum.selftest.gitbranch=main
```

日志中应出现 `[NSUM-TEST]` 三项 `PASS`（实例标记读写、Runtime 安装、真实同步）。
