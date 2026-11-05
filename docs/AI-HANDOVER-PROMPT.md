# 给外部 AI 的交接提示词（直接复制粘贴）

> 用法：把下面「=== 复制开始 ===」到「=== 复制结束 ===」之间的内容整段发给对方 AI。
> 适用于「对方改代码出 patch、你在设备上 `git am` + push」这种协作方式。

=== 复制开始 ===

你在为一个 Android 项目做代码修改，但**只出 patch，不直接推送**。目标是让 patch 能在设备上用
`git am` 干净应用。

## 项目

- 仓库：https://github.com/XWWP/HeadsetNotifier （公开，分支 `main`）
- 当前基线：tag `v2.7.5`，commit `69949b9`（请基于这个版本改，不要基于更新的假设）
- 应用：「耳机通知播报」`com.sakura.airannounce`，连接蓝牙耳机时朗读新通知
- 技术栈：纯 Java + Android framework API，**无 XML 布局、无第三方 UI 库**；界面全在 `MainActivity` 里用代码构建
- 构建：Gradle 8.10.2 + AGP 8.8.2 + JDK 21，`compileSdk 35 / minSdk 26 / targetSdk 34`

## 动手前必读（仓库内）

1. `HANDOVER.md` —— 环境、构建、推送、代码约定、实测清单
2. `docs/POWER-REVIEW-RESPONSE.md` —— 功耗评审的处置记录，**里面已经否掉的提案不要重复提**
3. 相关源码：`app/src/main/java/com/sakura/airannounce/`、`keepalive/service.sh`

## 硬性约束

- **不要修改** `gradle.properties`（里面的 `android.aapt2FromMavenOverride` 是本机 ARM 设备专用）、`local.properties`、`.github/workflows/android.yml`
- 不要引入第三方依赖（保持"无依赖、不联网"的定位）；除非必要且在说明里写清理由
- 文件编码 UTF-8（不带 BOM）；仓库里部分文件是 CRLF，**改动时保持原文件的换行风格**，不要整文件重排
- 不要"顺手格式化"无关代码，patch 里只包含与问题直接相关的行，方便 review
- 回退用户可见行为时需要同步改文案（`res/values/strings.xml` 或代码里的中文）

## 输出格式（重要）

请输出 **`git format-patch` 标准格式**的 patch，能直接 `git am` 应用：

```
From <40 位 sha 风格占位或真实 sha> Mon Sep 17 00:00:00 2001
From: 你的名字 <noreply@example.com>
Date: ...
Subject: [PATCH] 一句话说清改了什么

正文：为什么这么改、影响范围、验证方式（如有真机数据请写明）

---
 文件变更统计

diff --git a/... b/...
...
--
2.x.x
```

如果一次改动包含多个独立主题，请拆成多个 patch 文件（`0001-xxx.patch`、`0002-xxx.patch`）。

**如果不能确定格式**，退而求其次：给出完整 unified diff（`diff --git` 开头，含 `--- a/...` / `+++ b/...`），并在开头用文字写明建议的提交信息，我会手工 `git apply` 后自己提交。

## 提交信息规范

- 首行：`<版本号或主题>：<做了什么>`，例如 `2.7.6：修复拔耳机后队列残留`
- 正文用 `-` 列表，逐条说明「改了什么 / 为什么 / 影响」
- 涉及行为变更（默认值、时序、权限）必须单独点出来

## 版本号规则

- 改应用行为：`app/build.gradle.kts` 的 `versionCode` +1、`versionName` 递增（当前 22 / 2.7.5）
- 只改文档：不动版本号
- 我会在应用 patch 后打 tag（`vX.Y.Z`）触发 CI，所以**版本号必须和提交里写的一致**

## 改完自检

- Java：语法/引用自查（本地无法编译，写清楚你依据的 API 与 minSdk 是否兼容）
- Shell（`keepalive/service.sh`）：必须是 **dash/busybox ash 兼容**语法，不要用 bashism（数组、`[[ ]]`、`local` 之外的 bash 特性）
- 涉及播报逻辑的改动，在提交信息里列出需要真机验证的场景（连发相同消息、来电打断、拔耳机音量、关开关静默）

## 你可以问我

如果缺少上下文（日志、`dumpsys` 输出、真机现象），直接说明需要什么，不要猜着改。

=== 复制结束 ===

## 你在设备侧怎么应用 patch

```bash
cd /workspace/android-build/airannounce
git status                       # 确认工作区干净
git am 0001-xxx.patch            # 单个；多个按顺序
git log --oneline -3             # 看进来没有
bash /workspace/build.sh         # 编译验证（可选但推荐）
git push origin main
git tag -a vX.Y.Z -m "X.Y.Z" && git push origin vX.Y.Z   # 触发 CI
```

`git am` 冲突时：`git am --abort` 回滚，让对方基于最新基线重出 patch，不要手工硬解。
