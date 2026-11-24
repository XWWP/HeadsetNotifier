# 归档

这里存放**已处置完毕的一次性报告**：评审记录、测量数据、方案提案、某次自检。
它们仍有参考价值（能查到「当初为什么这么定」），但不再代表当前行为。

## 当前有效的文档（`docs/` 根目录）

| 文件 | 内容 |
| --- | --- |
| `docs/TAKEOVER.md` | 接手手册、代码约定与踩坑记录 |
| `docs/CI.md` | CI 流程与签名说明 |
| `docs/AI-HANDOVER-PROMPT.md` | 交接提示词基线（版本号需随发版更新） |

## 归档清单

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| `POWER-REVIEW.md` | 功耗与检测策略评审（对象 2.7.4 + keepalive v1.2） | 已处置，见下一条 |
| `POWER-REVIEW-RESPONSE.md` | 上述评审的逐条处置记录（2.7.5） | 已完成 |
| `MEASUREMENT-INTERVAL.md` | 5 秒 vs 20 秒、亮屏 vs 息屏四组对照 | 结论已落进模块 |
| `MEASUREMENT-POWER-2026-11-09.md` | 2026-11-09 功耗实测 | 一次性 |
| `MEASUREMENT-REFREEZE.md` | 复冻测量 | 一次性 |
| `FREEZE-TEST.md` | 冻结测试记录 | 一次性 |
| `POLLING-PLAN.md` | 轮询方案计划 | 已实施 |
| `PROPOSAL-REVIEW.md` | 提案评审 | 已处置 |
| `PROPOSAL-LISTENER-HEAL.md` | 监听自愈方案 | **未采纳**（v1.7+ 作废） |
| `SELF-CHECK-2026-11-06.md` | 2026-11-06 自检 | 一次性 |
| `measurements/` | 上述测量的原始数据 | 只读留档 |

## 归档规则

- 带日期的一次性报告，结论已落进代码或 `HANDOVER.md` 后，挪到这里；
- 移动时同步更新 `HANDOVER.md` 的文档索引；
- 归档文件不再改动内容；需要更正时在 `HANDOVER.md` 里追加说明。
