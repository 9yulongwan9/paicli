# Phase 23: 代码检索子 Agent（Explore / Dispatch Agent）

本阶段目标：对齐 Claude Code 的 Explore / Task agent 设计，新增一个**只读的代码检索子 Agent**。当主 Agent 预判某个子任务需要多轮检索时，把整段检索委托给子 Agent，子 Agent 在**独立上下文**里跑多轮 `glob_files` / `grep_code` / `read_file`，只把**精炼结论**回传，主 Agent 上下文只增长一条结果消息而非几十条 `tool_result`。

参考：Claude Code 的 Agent / Task 工具（dispatch agent，隔离上下文 + 返回压缩结果）。

## 背景与动机

ReAct 主循环的会话压缩（`ConversationHistoryCompactor`）只能在 user 消息边界切：判断条件是 `userIndices.size() <= retainRecentRounds`（默认 3）就跳过。因此**单个 user 轮次内**的 `glob_files` / `grep_code` / `read_file` 洪流压缩器救不了——这一轮没有新的 user 消息可供切分，最终只能被 `AgentBudget` 的迭代 / token 上限粗暴截断。

本项目的 `SubAgent` 已经具备隔离上下文（独立 `conversationHistory`）、独立 `AgentBudget`、独立 `maybeCompactHistory`、返回单条 `AgentMessage` 的能力，天然适配“探索子 Agent”这一模式。本阶段是**集成需求**：补齐“触发判断 + 作为工具暴露 + 只读检索角色 + 接口解耦”。

## 设计决策（已锁定）

| 维度   | 决定                                                | 理由                                                               |
| ---- | ------------------------------------------------- | ---------------------------------------------------------------- |
| 触发方式 | **模型自决 + 确定性预检兜底**                                | 暴露 `explore_codebase` 工具让 LLM 预判派发（主路径），叠加循环内检索计数预检防止模型不主动派发（兜底） |
| 结果粒度 | **仅摘要 + `file:line`**                             | 上下文节省最大化；主 Agent 需要细节时自己 `read_file`，不在报告里内联代码片段                 |
| 角色实现 | **复用 `AgentRole.WORKER` + 受限工具视图**                | 不动 `AgentRole` 枚举与现有提示词体系，改动面最小                                  |
| 解耦方式 | **tool 包定义端口接口 `CodeExploreRunner`，装配层注入 lambda** | 避免 `tool → agent → tool` 包循环依赖；`ToolRegistry` 不认识 `SubAgent`     |

## 目标 / 非目标

目标：

- 主 Agent 预判子任务需多轮检索时，可整体委托只读子 Agent。
- 主 Agent 上下文增量从 `O(工具调用数)` 降到 `O(1)` 条精炼报告。
- 复用现有 `SubAgent` / `AgentBudget` / `ToolRegistry.executeTools` 并行能力，不另起炉灶。

非目标：

- 不改 RAG / `VectorStore`（`search_code` 仍是语义辅助，子 Agent 可用但非本阶段重点）。
- 不改 HITL 拦截顺序、不做容器 / VM 沙箱。
- 不替代主 Agent 的轻量检索：一两次 grep / read 仍走主 Agent 直接做，不强制派发。

## 架构概览

```text
主 Agent (Agent.java)
  └─ tool: explore_codebase            ← LLM 自决派发
        └─ CodeExploreRunner (tool 包端口接口, 装配层注入实现)
              └─ SubAgent("explorer", WORKER, llmClient, readOnlyView)
                    ├─ 只读工具视图: glob_files / grep_code / read_file / list_dir / search_code
                    ├─ 独立 conversationHistory / AgentBudget / 压缩
                    └─ 返回 AgentMessage(精炼报告: 结论 + file:line + 建议读取)
```

依赖方向：`agent` 包依赖 `tool` 包（既有方向）。装配层（`agent` / `cli`）构造 `SubAgent` 并以 lambda 注入 `ToolRegistry.setExploreRunner(...)`，`ToolRegistry` 只认 `CodeExploreRunner` 接口，不反向 import `SubAgent`，无包循环。

## 23.0 全链路时序（派发路径调用链）

一次「预计多轮检索」的请求，完整调用链如下。两处关键点：①`ToolRegistry`(tool 包) 经 `CodeExploreRunner` 接口跳到装配层起 `SubAgent`，靠接口缝避免 `tool → agent` 包循环；②`SubAgent.execute` 内部是**一整个 ReAct 循环**，同步套在主 Agent 的一次 `executeToolCalls` 里跑完才返回（循环里嵌循环）。

```text
用户输入
  │
  ▼
Main CLI 循环  ──►  reactAgent.run(userInput)            [Agent.java:122]
                      │  while(true) 主 ReAct 循环
                      ▼
                    llmClient.chat(history, 工具表, …)    [Agent.java:177]
                      │  ← 工具表含 explore_codebase
                      ▼
                    response.hasToolCalls()?
                      │  LLM 自决:预感要多轮检索 → 调 explore_codebase   ← 触发(见 23.5)
                      ▼
                    Agent.executeToolCalls(toolCalls)     [Agent.java:210]
                      ▼
                    toolRegistry.executeTools(invocations)   ← 并行调度
                      ▼
                    Tool("explore_codebase").handler(args)   [ToolRegistry, 见 23.1]
                      │  args.get("task") / args.get("focus_paths")
                      ▼
                    exploreRunner.explore(task, focus)       [CodeExploreRunner 接口]
                      │  ↑ 实现是装配层注入的 lambda(见 23.3)
                      ▼  ════ 跨进 agent 包,起子 Agent ════
                    readOnly = mainTools.readOnlyExploreView()   [见 23.2 播种式拷贝]
                      │  复制 projectPath/contextProfile/searchProvider,裁剪成 5 个只读工具
                      ▼
                    new SubAgent("explorer", WORKER, llmClient, readOnly)  [SubAgent.java:55]
                      ▼
                    explorer.execute(task, 静默流)          [SubAgent.java:161]
                      │  ┌── while(true) 子 Agent 自己的 ReAct 循环(十几轮)──┐
                      │  │  llmClient.chat(私有 history, 5 个只读工具)  [:195] │
                      │  │  hasToolCalls? → executeToolCalls          [:318]  │
                      │  │     ▼                                              │
                      │  │  toolRegistry.executeTools  →  grep_code 等        │
                      │  │     ▼                                              │
                      │  │  grepCode(args)            [ToolRegistry:423]      │
                      │  │     ▼                                              │
                      │  │  new RipgrepCodeSearchEngine(...).search(req)      │
                      │  │     ▼  command() 拼 rg --json … 命令行   [:138]    │
                      │  │     ▼  跑 rg(或 Java 回退),结果进私有 history      │
                      │  │  continue → 下一轮 …                               │
                      │  │  某轮 LLM 不再调工具,输出"## 结论/命中/建议读取"   │
                      │  └────────────────────────────────────────────────┘ │
                      │  return AgentMessage.result(报告)         [:233]      │
                      ▼  ════ 退回 tool 包 ════
                    explore() 返回报告字符串(.content())
                      ▼
                    作为 explore_codebase 的 tool 结果
                      ▼
                    conversationHistory.add(Message.tool(id, 报告))  [Agent.java:213]
                      │  ← 主 Agent 上下文只 +1 条报告,不是几十条 grep 原文
                      ▼
                    continue 主循环 → llmClient.chat 带着报告继续
                      ▼
                    LLM 基于报告(可能再自己 read_file 精确行段)→ 最终答复
                      ▼
                    返回给用户
```

分层职责：

| 层 | 类 / 方法 | 职责 |
|------|------|------|
| 入口 | `Main` CLI 循环 → `Agent.run` | 主 ReAct 循环 |
| 决策 | `llmClient.chat` 返回 `explore_codebase` 调用 | LLM 自决是否派发（见 23.5） |
| 调度 | `Agent.executeToolCalls` → `ToolRegistry.executeTools` | 工具调用并行跑 |
| 工具 | `explore_codebase` handler（`ToolRegistry`，见 23.1） | 转手调 `exploreRunner.explore` |
| 端口 | `CodeExploreRunner.explore`（接口） | tool 包 ↔ agent 包的解耦缝 |
| 实现 | 装配层 lambda（`Main`，见 23.3） | 建只读视图 + 起 `SubAgent` |
| 视图 | `ToolRegistry.readOnlyExploreView`（见 23.2） | 播种式拷贝出只读注册表 |
| 执行体 | `SubAgent.execute`（agent 包） | explorer 自己的多轮 ReAct 循环 |
| 底层检索 | `grepCode` → `RipgrepCodeSearchEngine.search` → `rg` | 真正跑 ripgrep（或 Java 回退） |

上下文与 HITL 差别：

- **上下文**：explorer 那十几轮的 grep/read 原文全留在 explorer 的私有 `conversationHistory`（`SubAgent.java:48`），用完即弃；主 Agent 只收到 1 条报告 → 主上下文增量从 `O(检索次数)` 降到 `O(1)`。
- **HITL**：全程不弹审批。`explore_codebase` 不在 `DANGEROUS_TOOLS`；explorer 的视图是普通 `ToolRegistry`（非 `HitlToolRegistry`）且只含只读工具。

对比——轻量检索路径（未派发，本期不变）：LLM 不调 `explore_codebase`，直接调 `grep_code`，链路为 `Agent.run → executeToolCalls → ToolRegistry.executeTools → grepCode → RipgrepCodeSearchEngine.search → rg`，grep **原文直接进主 Agent 上下文**，没有 `CodeExploreRunner` / `SubAgent` 这一段。`explore_codebase` 只是多给 LLM 一个「预感要查很多次就外包」的选项，不强制。

## 23.1 端口接口与工具注册

目标：在 `ToolRegistry` 暴露 `explore_codebase`，沿用既有“可选协作者 setter 注入 + handler `== null` 优雅降级”模式（参考 `searchProvider` / `memorySaver` / `browserConnector`）。

新增端口接口（`tool` 包下唯一新增类型，函数式接口）：

```java
package com.paicli.tool;

@FunctionalInterface
public interface CodeExploreRunner {
    /** 跑一个只读检索子 Agent，返回精炼报告（结论 + file:line + 建议读取行段）。*/
    String explore(String task, String focusPaths);
}
```

`ToolRegistry` 改动（一个字段 + 一个 setter + 一处 `tools.put`）：

```java
private CodeExploreRunner exploreRunner;            // 默认 null
public void setExploreRunner(CodeExploreRunner r) { this.exploreRunner = r; }
```

```java
// registerCodeTools() 内注册
tools.put("explore_codebase", new Tool(
    "explore_codebase",
    "委托只读子 Agent 做多轮代码检索；当预计需要多次 grep/read、位置不确定、需跨多个文件定位时优先用它，只返回精炼结论而非大量中间结果",
    createParameters(
        new Param("task", "string", "自然语言检索目标，例如'找到 grep_code 工具的注册与执行链路'", true),
        new Param("focus_paths", "string", "可选，缩小搜索范围，例如 src/main/java/com/paicli/tool", false)
    ),
    args -> exploreRunner == null
        ? "探索子 Agent 未启用"
        : exploreRunner.explore(args.get("task"), args.get("focus_paths"))
));
```

验收：

- `ToolRegistryTest` 断言 `explore_codebase` 工具定义存在、参数 schema 正确。
- 未注入 `exploreRunner` 时调用返回“未启用”而非抛异常。

## 23.2 只读工具视图（防递归 + 强制只读）

目标：给子 Agent 用的 `ToolRegistry` 视图只暴露只读检索工具，物理排除写 / 执行工具与 `explore_codebase` 自身。

> ⚠️ **关键约束：不能用 `new ToolRegistry()` + `retainAll`。
> ** `ToolRegistry` 的每个工具是构造时注册的 lambda，闭包捕获的是「该实例自己的字段」。
> `new ToolRegistry()` 会把 `projectPath` 重置为 `System.getProperty("user.dir")`（JVM 启动目录），
> `pathGuard` / `lspManager` / `snapshotService` 随之指向该默认目录，`contextProfile` 退回默认档，`searchProvider` 为 `null`。
> 若主 Agent 通过 `setProjectPath()` 改过工程根，子视图就会**搜错目录、PathGuard 拦错、`search_code` 静默失效**。
> `retainAll` 只过滤「保留哪些 handler」，根本不影响这些 handler 读到的状态。

因此必须改为「**播种式拷贝**」：从源注册表显式复制检索相关状态，再裁剪工具集。每个字段都要做一次「共享 / 复制 / 丢弃」的显式决策。

```java
public ToolRegistry readOnlyExploreView() {
    ToolRegistry view = new ToolRegistry();
    // —— 复制（必须与主 Agent 一致，否则搜错目录 / 档位错 / RAG 失效）——
    view.setProjectPath(this.projectPath);        // 级联重建 pathGuard / lspManager（setProjectPath 内部）
    view.setContextProfile(this.contextProfile);  // 压缩触发阈值与主 Agent 一致
    view.setSearchProvider(this.searchProvider);  // 白名单含 search_code，缺它则降级
    // —— 故意丢弃（只读路径用不到，且能避坑）——
    //   memorySaver / browserGuard / writeFileObserver：只读无关
    //   snapshotService：避免对同一工程起第二个快照服务（setProjectPath 会 close+重建，需绕过）
    // —— 裁剪：只保留只读检索工具 ——
    view.tools.keySet().retainAll(Set.of(
        "glob_files", "grep_code", "read_file", "list_dir", "search_code"));
    return view;
}
```

> 实现注意：`setProjectPath()`（`ToolRegistry.java:133`）在 `!customSnapshotService` 时会 `snapshotService.close()` 再 `forProject()`。
> 只读视图不需要快照能力，应避免这次重建（例如复制前置 `customSnapshotService=true`，或提供一个跳过快照重建的内部 setter），防止两个 `SnapshotService` 实例争用同一工程目录。

字段处置一览：

| 字段 | 处置 | 理由 |
|------|------|------|
| `projectPath` / `pathGuard` | **复制** | 否则搜错目录、PathGuard 拦错（硬 bug） |
| `contextProfile` | **复制** | 子 Agent 压缩触发阈值需与主 Agent 一致 |
| `searchProvider` / RAG | **复制** | 白名单含 `search_code`，缺它则静默失效 |
| `lspManager` | 随 `setProjectPath` 重建即可 | 只读检索不依赖诊断注入 |
| `currentModel` | 不处理 | `SubAgent` 构造器（`:60`）会 `setCurrentModel` 补齐 |
| `snapshotService` | **丢弃 / 跳过重建** | 只读用不到，避免双实例争用 |
| `memorySaver` / `browserGuard` / `writeFileObserver` | **丢弃** | 对应工具不在白名单，留着也不会被调 |

原则：

- 白名单只含只读工具 → 子 Agent 不可能触发 HITL（与 `ApprovalPolicy` 的 `DANGEROUS_TOOLS` 互斥）。
- 不含 `explore_codebase` → 杜绝无限递归嵌套。
- 检索相关状态（路径 / 档位 / RAG）必须**显式复制**而非依赖默认构造，保证子 Agent 与主 Agent 搜索范围、上下文档位、检索能力一致。

验收：

- `ExplorerSubAgentTest` 断言子 Agent 工具集不含 `write_file` / `execute_command` / `create_project` / `revert_turn` / `explore_codebase`。
- 子 Agent 误调写工具时被拒（白名单生效）。

## 23.3 装配层接线（agent / cli 层）

目标：在 `agent` 包（方向合法）把 `SubAgent` 接到 `CodeExploreRunner`。

```java
mainTools.setExploreRunner((task, focus) -> {
    ToolRegistry readOnly = mainTools.readOnlyExploreView();
    SubAgent explorer = new SubAgent("explorer", AgentRole.WORKER, llmClient, readOnly);
    String prompt = buildExplorePrompt(task, focus);   // 输出契约见 23.4
    AgentMessage result = explorer.execute(AgentMessage.task("main", prompt), foldedOut);
    return result.content();
});
```

原则：

- `llmClient` 留在装配层 lambda 闭包里，不进 `ToolRegistry` 字段。
- `foldedOut` 决定子 Agent 探索过程的展示策略（默认折叠 / 静默，只把最终报告落到主 transcript；可后续做成可展开）。
- 并发：多个 `explore_codebase` 经 `executeTools` 并行时，每个都会起一个子 Agent，需要控制并发上限（复用 `MAX_PARALLEL_TOOLS=4` 或单独设更小值），避免同时起多个 LLM 子会话。

## 23.4 Explorer 输出契约

目标：固定子 Agent 的返回格式，确保“仅摘要 + `file:line`”，并设字符硬上限（仿 `grep_code` 的 `max_chars`）。

通过 `buildExplorePrompt(task, focus)` 在子 Agent 任务提示词里约束输出：

```text
## 结论
<2-4 句话直接回答 task>

## 命中
- path/to/File.java:123 — 一句话说明该处与 task 的关系
- ...

## 建议下一步读取
- read_file {"path":"...","offset":...,"limit":...}
```

原则：

- 不内联代码片段；命中项只给 `file:line` + 一句话。
- 超字符上限则截断并标 `partial: true`，提示主 Agent 收窄 task 或自行 `read_file`。
- 报告是给主 Agent 看的中间产物，措辞精炼、不寒暄。

验收：

- `ExplorerSubAgentTest` 断言报告含“结论 / 命中 / 建议下一步读取”三段结构且未超字符上限。

##












## token 计费与展示

- 子 Agent 的 token 走自己的 `AgentBudget`；需明确是否汇总进主 Agent 的底部 dock `ctx` / `in/out` 统计（建议：子 Agent token 计入本轮任务总量，但 `ctx`（下一轮带入上下文）只反映主 Agent 历史，避免混淆）。
- 子 Agent 探索过程默认折叠 / 静默，主 transcript 只显示“🔍 探索代码库…”进度与最终报告。

## 边界 / 风险

- **递归嵌套**：靠 23.2 白名单物理阻断，不能仅靠提示词。
- **失败回退**：子 Agent 超 budget / LLM 失败时返回错误报告，主 Agent 可据此回退到自己检索；`explore` 实现需捕获异常并返回可读文本而非抛出。
- **并发**：控制同时运行的子 Agent 数量。
- **包依赖**：`ToolRegistry` 不得 import `agent` 包任何类型，只依赖 `CodeExploreRunner` 接口。

## 文档与联动（修改硬规则 4：改工具集）

- `ToolRegistry.java` + Agent / Worker 提示词 + `AGENTS.md`（核心内置工具 11 → 12）+ `README.md` 同步。
- `AGENTS.md` 架构概览补充：代码库理解默认走实时探索；预计多轮检索时派发 `explore_codebase` 只读子 Agent。

## 验证路径

| 场景 | 命令 |
|------|------|
| 工具定义 / 只读视图 / 输出契约 | `mvn test -Dtest=ToolRegistryTest,ExplorerSubAgentTest` |
| 触发预检 | `mvn test -Dtest=AgentExplorePrecheckTest`（新增） |
| 常规回归 | `mvn test -Pquick` |

手工验收：给一个“需跨多文件定位”的任务，对比派发前后主 Agent `conversationHistory` 的 token 增量（应显著下降），并确认子 Agent 不递归、不触发 HITL。

## 交付边界

本阶段交付：`explore_codebase` 工具 + `CodeExploreRunner` 端口 + 只读视图 + 输出契约 + 模型自决提示词 + 确定性预检兜底。

后续可选增强：子 Agent 探索过程可展开展示、并发探索调度优化、把预检从“建议”升级为“软劫持”。