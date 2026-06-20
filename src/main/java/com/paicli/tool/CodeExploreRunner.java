package com.paicli.tool;

/**
 * 代码检索子 Agent 的端口接口（tool 包 ↔ agent 包的解耦缝）。
 *
 * <p>{@code ToolRegistry} 只认这个接口,不反向 import {@code SubAgent},避免
 * {@code tool → agent → tool} 包循环依赖。具体实现由装配层（agent / cli）以 lambda
 * 注入：建只读工具视图 + 起只读检索子 Agent,返回精炼报告。
 */
@FunctionalInterface
public interface CodeExploreRunner {

    /**
     * 跑一个只读检索子 Agent,返回精炼报告（结论 + file:line + 建议读取行段）。
     *
     * @param task       自然语言检索目标
     * @param focusPaths 可选的搜索范围缩小提示,可为 null
     * @return 精炼报告文本；实现需自行捕获异常并返回可读文本,不向上抛出
     */
    String explore(String task, String focusPaths);
}
