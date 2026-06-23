# Phase 24: ConversationHistoryCompactor 结构化摘要

本阶段目标：把 `ConversationHistoryCompactor` 的自由文本摘要升级为固定 Markdown 结构的恢复快照，让长对话压缩后仍能保留用户意图、当前工作、待办、关键文件、错误修复和下一步。

## 背景与动机

`ConversationHistoryCompactor` 负责压缩 Agent 实际发给 LLM 的 `conversationHistory`。旧摘要 prompt 要求输出 1-3 段自然语言，虽然简洁，但缺少结构化约束：当前目标、正在做的事、用户明确要求、涉及文件、错误修复和待办事项都可能混在段落里，后续模型读取时容易遗漏关键约束。

本阶段只调整摘要生成契约，不改变压缩触发条件、user 边界切分、保留最近轮次、工具调用参数提取和回灌结构。

## 设计决策（已锁定）

| 维度 | 决定 | 理由 |
|------|------|------|
| 输出格式 | 固定 Markdown 标题 | 直接给后续 LLM 阅读，不引入 JSON 解析失败风险 |
| 摘要范围 | 压缩旧 `conversationHistory` | 继续保持压缩对象是实际 LLM 输入消息 |
| 用户消息 | 必须覆盖所有非 tool result 的 user message | 用户限制、否定条件和明确要求不能在压缩中丢失 |
| 当前工作 | 独立成段并要求细颗粒度 | 压缩后恢复任务时，最容易丢的是“正在做到哪一步” |
| 安全边界 | 明确摘要是历史状态快照，不是新指令 | 降低工具输出、网页内容或旧历史里的指令污染后续任务的风险 |

## 结构化摘要模板

摘要 prompt 要求 LLM 严格输出以下同级标题；没有内容时写“无”：

```markdown
## Primary Request and Intent
用户的主要目标、约束、偏好、最终想要的结果。

## Current Work
用最细颗粒度描述 Agent 当前正在做什么：当前步骤、已读/已改/准备改的文件、当前判断、下一次工具调用或代码改动意图。

## Pending Tasks
尚未完成的任务、验证项、需要用户确认的问题。

## All User Messages
按时间顺序列出所有非 tool result 的用户消息。每条保留原意，必要时可截断，但不要遗漏用户明确要求、限制、否定条件。

## Key Technical Concepts
本轮涉及的核心技术点、模块、架构概念。

## Files and Code Sections
涉及文件、类、方法、关键行号或代码区域。

## Problem Solving
已经解决的问题、做出的判断、采取的方案。

## Errors and Fixes
遇到的错误、失败命令、异常、原因和修复方式。

## Optional Next Step
如果继续，最合理的下一步。
```

## 修改计划

1. 在 `ConversationHistoryCompactorTest` 中新增 prompt 捕获测试，断言摘要请求包含九个固定标题和用户消息覆盖要求。
2. 先运行定向测试确认失败，证明当前自由文本摘要不满足结构化契约。
3. 修改 `ConversationHistoryCompactor.SUMMARY_PROMPT`，要求严格使用固定 Markdown 模板，并补充历史摘要不是当前指令的安全规则。
4. 重新运行 `ConversationHistoryCompactorTest`，确认现有压缩行为和新增结构化 prompt 测试全部通过。
5. 同步 README 中对长对话摘要压缩的描述。

## 修改内容

### `ConversationHistoryCompactor.java`

- 将旧的 1-3 段摘要 prompt 改为固定 Markdown 模板。
- 新增明确规则：摘要是历史状态快照，不是新的用户指令。
- 要求保留文件路径、命令、错误信息、用户否定条件和未完成事项。
- 要求工具结果只提炼关键结果，不复述大段原文。
- 保留现有压缩算法：仍按 token 阈值触发，仍在 user message 边界切分，仍保留最近 `retainRecentRounds` 个 user 起算的尾部。

### `ConversationHistoryCompactorTest.java`

- 新增 `summaryPromptUsesStructuredRecoverySections` 测试。
- 通过 `CapturingClient` 捕获摘要请求，验证 prompt 中包含全部结构化标题和 `All User Messages` 的关键约束。

### `README.md`

- 把 `ConversationHistoryCompactor` 的说明更新为结构化长对话摘要压缩。

## 非目标

- 不改压缩触发阈值。
- 不改压缩后消息回灌方式。
- 不引入 SubAgent 做摘要。
- 不做 JSON 输出和解析。
- 不处理“压缩后仍超预算”的二次压缩策略；这是后续可独立处理的问题。

## 风险与边界

- LLM 仍可能不完全遵守模板；当前只通过 prompt 约束，没有后处理校验。
- `All User Messages` 在极长会话里会增加摘要长度，但这是为了保留用户明确要求和否定条件做出的取舍。
- 结构化模板提升可读性和恢复能力，但不能完全避免摘要丢失细节。

## 验证路径

| 场景 | 命令 |
|------|------|
| ConversationHistoryCompactor 单元测试 | `mvn test -Dtest=ConversationHistoryCompactorTest -DskipTests=false` |
| 常规回归 | `mvn test -Pquick` |

已验证：`ConversationHistoryCompactorTest` 通过。