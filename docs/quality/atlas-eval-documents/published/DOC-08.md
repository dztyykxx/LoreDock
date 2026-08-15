---
documentId: DOC-08
title: 场景包状态生命周期
directory: 场景包管理/状态
status: PUBLISHED
scope: PROJECT
project: atlas
updatedAt: 2026-08-15
---

# 场景包状态生命周期

## 状态定义

导入批次在生命周期内会处于以下状态：

- `UPLOADED`：包已上传，等待开始校验；
- `VALIDATING`：正在执行格式与内容校验；
- `PENDING_REVIEW`：校验通过，等待 ADMIN 人工审核；
- `PUBLISHED`：审核通过并正式发布；
- `REJECTED`：审核被拒绝，需要修改后重新提交；
- `FAILED`：处理失败且自动重试后仍失败，等待 ADMIN 人工处理。

## 状态转换表

| 前置状态 | 转换动作 | 结果状态 |
|---|---|---|
| `UPLOADED` | 开始校验 | `VALIDATING` |
| `VALIDATING` | 校验通过 | `PENDING_REVIEW` |
| `VALIDATING` | 校验失败且重试后仍失败 | `FAILED` |
| `PENDING_REVIEW` | ADMIN 审核通过并发布 | `PUBLISHED` |
| `PENDING_REVIEW` | ADMIN 审核拒绝 | `REJECTED` |
| `FAILED` | ADMIN 发起人工重试 | `VALIDATING` |

## 主要状态转换

- `UPLOADED` → `VALIDATING`：上传完成后开始校验；
- `VALIDATING` → `PENDING_REVIEW`：校验通过，进入待审核；
- `PENDING_REVIEW` → `PUBLISHED`：ADMIN 审核通过并发布；
- `PENDING_REVIEW` → `REJECTED`：ADMIN 审核拒绝；
- `VALIDATING` → `FAILED`：校验或处理失败，自动重试后仍失败；
- `FAILED` → `VALIDATING`：ADMIN 发起人工重试后重新处理。

## 转换规则

状态转换由平台流程驱动，普通成员不能手动改写批次状态。`REJECTED` 批次需要通过新幂等键重新上传形成新批次来再次进入流程，原批次保留在历史记录中。`PUBLISHED` 是正式知识状态，之后不再回到校验或待审核；已发布文档只能归档，不能删除。

## 终态与驻留

`PUBLISHED`、`REJECTED`、`FAILED` 是批次的终态。终态批次不再自动流转；其中 `REJECTED` 和 `FAILED` 可以通过新上传或人工重试重新进入流程，`PUBLISHED` 之后只能通过归档退出检索。批次停留在待处理状态期间，处理记录保留在批次详情中，不会因状态不变而丢失。

## 状态与可见性

状态决定正文是否可检索。只有 `PUBLISHED` 的正文进入普通用户检索和导出范围；`PENDING_REVIEW`、`REJECTED`、`FAILED` 等状态的正文都属于候选或待处理内容，不进入普通检索。上传者可以查看自己批次的状态，ADMIN 可以查看全部批次，可见范围不随状态扩大。`UPLOADED` 与 `VALIDATING` 是短暂的中间状态，通常在处理过程中快速通过，上传者通过批次详情即可查看当前所处阶段。

## 状态与通知、审计的对应

进入 `FAILED`、被审核拒绝、正式发布等关键状态变化都会触发通知并记录审计事件。上传者和管理员可以通过批次详情查看当前状态和已经过的转换路径，确认批次处理到哪一步、后续由谁负责。
