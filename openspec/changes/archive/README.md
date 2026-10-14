# 归档变更

本目录保存已完成变更的**提案原件**：变更当时的 `proposal.md` / `design.md` / `tasks.md`，
以及 `specs/` 下的**增量规格**。

## 与 `openspec/specs/` 的关系

同名能力的规格在本目录与 [`openspec/specs/`](../../specs/) 下各有一份，内容高度重合，
差异只在文件头：

| | 本目录（archive） | `openspec/specs/` |
| --- | --- | --- |
| 文件头 | `## ADDED Requirements` | `## Requirements` + `## Purpose` |
| 语义 | 这次变更**新增了什么** | 变更合并后的**现状全貌** |

这不是重复维护，是归档机制本身的两层：**archive 回答「当时为什么这么改」，specs 回答「现在是什么样」**。
需要知道现状时看 `openspec/specs/`，需要追溯某条规格的来源与决策理由时看这里。

当前只有一次变更走完完整流程（多租户平台，2026-09-10），因此两组文件的 requirement /
scenario 计数完全一致（6 个能力，13 条 requirement，27 条 scenario）。后续若有变更结束归档，
两组就会分叉——那时 archive 侧只含增量，差异会自然拉开。

## 注意

归档件是**历史快照，不再回改**。如果发现 `openspec/specs/` 里的现状规格有错，
应新建一次变更去修正，而不是直接编辑本目录下的文件——否则就失去了「当时为什么这么改」这层信息。