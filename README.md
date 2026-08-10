# ZCSLIB — 内置自管指令平台

**ZCSLIB** 是 Minecraft NeoForge 模组的内置插件平台。模组主 JAR 加载 ZCSLIB，插件以独立 JAR 形式放在 `plugins/` 目录下，通过**字符串指令** `ctx.order("子系统:动作", args...)` 调用内核能力。

---

## 核心理念

- **字符串指令模式** — `ctx.order("service:register", ...) → OrderResult(success/fail)`，简单、可审计、无 magic getter
- **四级信任矩阵** — N / R / A / S，S 级默认全部拒绝
- **独立类加载** — PluginClassLoader 隔离每个插件，只暴露 API 包
- **自演化体系** — L1-L4 四阶记忆 + 梦擎（离线训练）+ 参数固化 + 压缩隔离
- **多版本兼容** — 同一 JAR 编译于 NeoForge 21.1，零修改运行于 26.1

---

## 5 分钟写出你的第一个插件

### 1. 创建 PEC.json

```json
{
  "contractSchema": "1.0",
  "pluginId": "hello-world",
  "version": "1.0.0",
  "entrypoint": "zcslib.helloworld.HelloPlugin",
  "environment": { "zcslib": ">=0.2.0" }
}
```

### 2. 包名约定

**推荐包名：`zcslib.[插件ID]`**

例如插件 ID `hello-world` → 包名 `zcslib.helloworld` → 文件路径 `src/main/java/zcslib/helloworld/HelloPlugin.java`

### 3. 写入口类

```java
package zcslib.helloworld;

import zcslib.api.PluginContext;

public class HelloPlugin {
    public HelloPlugin(PluginContext ctx) {
        ctx.getLogger().info("Hello, ZCSLIB! 我是 %s", ctx.getPluginId());
    }
}
```

### 4. 打包部署

```bash
gradle jar
cp build/libs/hello-world.jar config/DLZstudio/ZCSLIB/plugins/
```

---

## 目录结构

```
config/DLZstudio/ZCSLIB/
├── plugins/
│   └── {pluginId}/
│       ├── config/            # JSON 配置文件（用户可编辑）
│       ├── data/              # NBT 持久化数据（程序内部）
│       └── memory/            # 🆕 插件私有记忆
│           ├── l2/            # L2 短期运行日志
│           └── l3/            # L3 长期作战手册 (.zcsmem)
├── memory/                    # 🆕 全局记忆（Daemon 读写）
│   ├── l1/                    # L1 崩溃快照 (.zcsl1)
│   ├── l4/                    # L4 本能模式库 (.zcsinst)
│   └── training/              # 联邦训练集 (.zctsp)
├── audit/                     # 审计日志 N/R/A/S 分目录
│   ├── N/
│   ├── R/
│   ├── A/
│   └── S/
├── crash/                     # 内核崩溃日志
├── cache/{pluginId}/          # 临时缓存
├── shared_res/                # 共享只读资源
└── global/                    # 内核全局数据
```

---

## 文档索引

**技术文档（`docs/` 目录）**

| 编号 | 文档 | 内容 |
|:---:|:---|:---|
| 01 | [快速上手](docs/01-quickstart.md) | 10 分钟创建第一个插件 |
| 02 | [PEC 合约](docs/02-pec.md) | 插件身份证 JSON schema |
| 03 | [PluginContext](docs/03-plugincontext.md) | 8 个方法详解（含 ctx.order()） |
| 04 | [kernel.order()](docs/04-kernel-order.md) | 九子系统完整指令全集 |
| 05 | [事件总线](docs/05-event-bus.md) | @Subscribe + 跨插件通信 |
| 06 | [服务注册](docs/06-service-registry.md) | service:register/get/list |
| 07 | [调度器](docs/07-scheduler.md) | L3 计算池 + SyncQueue + Bulkhead |
| 08 | [配置与持久化](docs/08-config-pdc.md) | JSON (ConfigManager) vs NBT (PDCBackend) |
| 09 | [资源沙箱](docs/09-resource.md) | 路径穿越防护 + 磁盘配额 + memory/ 路径 |
| 10 | [信任体系](docs/10-trust-system.md) | N/R/A/S 四级 + 九子系统完整信任矩阵 |
| 11 | [MC 深度集成](docs/11-m6-mc-integration.md) | mcapi/sandbox/monitor/security |
| 17 | [Mixin 适配器](docs/17-mixin-adapter.md) | 插件 Mixin 声明 + refmap 版本重写 |

**决策与评估文档（`docs/` 目录）**

| 文档 | 内容 |
|:---|:---|
| [M7-M10 评估](docs/M7_M10_ASSESSMENT.md) | M7→M10 四里程碑难度/风险/工作量评估 |
| [AI 替换评估](docs/AI_REPLACEMENT_EVAL.md) | 自演化系统 AI 替换的产品 + 技术评估（结论：不替换） |
| [代码审查明细](docs/CODE_AUDIT_DETAILS.md) | 存量 + P15/P16 两次并行审查的问题明细 |
| [代码审查报告](docs/CODE_AUDIT_REPORT.md) | 全量审查综合评分 7.6/10 |
| [P15/P16 架构](docs/P15_P16_ARCHITECTURE.md) | M6 安全与监控模块架构设计 |

**开发归档（`docs/` 目录）**

| 文档 | 内容 |
|:---|:---|
| [开发日志 06-24](docs/P15_P16_DEVELOPMENT_LOG_2026-06-24.md) | P15/P16 实现 + BUILD.35/36 |
| [开发日志 06-30](docs/M6_COMPLETION_DEVELOPMENT_LOG_2026-06-30.md) | M6 完工归档（BUILD.40） |
| [编码修复记录](docs/DOC_REBUILD_UTF8_REPAIR_2026-06-13.md) | 包名迁移编码事故修复（GBK→UTF-8） |

---

## 信任等级速查

| 能力 | N | R | A | S |
|:---|---|---|---|:---:|
| 事件注册/发布 | ✅ | ✅ | ✅ | ⚠️/❌ |
| 服务注册/获取 | ✅ | ✅ | ✅ | ⚠️ |
| L3 计算 | ✅ | ✅ | ✅ | ❌ |
| 配置/NBT 读写 | ✅ | ✅ | ✅ | ✅ |
| 文件访问 | ✅ | ✅ | ✅ | ✅ |
| 网络主包发送 | ✅ | ✅ | ✅ | ❌ |
| 审计/记忆查询 | ✅ | ✅ | ✅ | ✅ |

---

## 构建

```bash
powershell -File .DLZstudio/buildid/build.ps1
```

产物命名：`ZCSLIB-{version}-BUILD.{buildid}_windows_amd64.jar`

---

## 版本历史

### v0.2.0-M7 (BUILD.00000045)
- Mixin 适配器（`mixin/` 纯 Java + 映射表 JSON）
- 多插件 Mixin config 合并为单一 `mixins.zcslib.json`
- Refmap 重写引擎（Mojang → 当前版本 obf）
- 21.1 / 26.1 版本映射表

### v0.2.0-M6 (BUILD.00000040)
- MC 深度集成：`mcapi/` + `sandbox/` + `monitor/` + `security/`
- ZCSLIBCommon 版本无关层拆分（跨 NeoForge 21.1↔26.1 零修改）
- 信任分级新增 `BLACKLISTED` / `UNKNOWN`
- 身份合并：dispatch 统一注入 `pluginId`，杜绝伪造
- 梦擎专属 McPort 三重安全门禁
- BanHammer 自动隔离 + 五维行为评分
- 82 个 .java 源文件

### v0.2.0-M5 "不死鸟" (BUILD.00000024)
- 9 个子系统全部接线（+ network / audit / memory）
- Daemon 守护进程（双 Main-Class，`--daemon` 入口）
- L1-L4 四阶记忆系统 + 梦擎 + 压缩体系
- 参数体系（全局/局部/双边 + 固化 + 奖惩闭环）
- AuditLogger（N/R/A/S 分目录 + 日期文件名）
- LogRotator（7 天审计保留 + crash 清理）
- ~55+ 个 .java 源文件

### v0.2.0-M4 (BUILD.00000019)
- 网络抽象层（ZCNetwork + MainPacketAssembler + OfflineQueue）
- 聚合器健康检查（AggregatorHealthCheck）
- 包名迁移 com.dlzstudio.zcslib → zcslib

### v0.2.0-M3 (BUILD.00000016)
- 28 个 .java 源文件
- 6 个子系统全部接线
- ZCSLIB-TestPlugin 集成测试通过
- PluginClassLoader 隔离加载
- PEC 扫描/校验/版本比较
- ComputePool + SyncQueue + Bulkhead
- ConfigManager 原子写入
- PDCBackend NBT 持久化
