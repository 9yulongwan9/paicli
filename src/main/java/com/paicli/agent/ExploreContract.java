package com.paicli.agent;

/**
 * Explorer 子 Agent 的输出契约（Phase 23.4）。
 *
 * <p>固定子 Agent 返回的报告格式为「仅摘要 + {@code file:line}」三段结构,并对报告施加字符硬上限
 * （仿 {@code grep_code} 的 {@code max_chars}）。装配层（23.3）用 {@link #buildPrompt} 生成子 Agent
 * 的任务提示词,用 {@link #clampReport} 在回传前对报告施加上限。
 */
public final class ExploreContract {

    /**
     * 报告字符硬上限。探索报告只含结论 + {@code file:line} + 建议读取,预算应远小于 {@code grep_code}：
     * 命中细节由主 Agent 自行 {@code read_file},不在报告里内联代码片段。
     */
    public static final int MAX_REPORT_CHARS = 4_000;

    private static final String TRUNCATION_MARKER =
            "\n\n...(报告已截断，partial: true；请收窄 task 或自行 read_file 精确行段)";

    private ExploreContract() {
    }

    /**
     * 构造只读检索子 Agent 的任务提示词,约束其严格按「结论 / 命中 / 建议下一步读取」三段输出。
     *
     * @param task       自然语言检索目标
     * @param focusPaths 可选的搜索范围限定,可为 null / 空
     */
    public static String buildPrompt(String task, String focusPaths) {
        StringBuilder sb = new StringBuilder();
        sb.append("检索目标：").append(task == null ? "" : task.trim()).append("\n");
        if (focusPaths != null && !focusPaths.isBlank()) {
            sb.append("搜索范围限定：").append(focusPaths.trim()).append("\n");
        }
        sb.append("\n")
          .append("你是只读检索子 Agent。用 glob_files / grep_code / read_file / list_dir / search_code 多轮检索，")
          .append("定位后严格按以下格式输出报告，不要寒暄、不要内联代码片段：\n\n")
          .append("## 结论\n")
          .append("<2-4 句话直接回答检索目标>\n\n")
          .append("## 命中\n")
          .append("- path/to/File.java:123 — 一句话说明该处与目标的关系\n")
          .append("- ...\n\n")
          .append("## 建议下一步读取\n")
          .append("- read_file {\"path\":\"...\",\"offset\":...,\"limit\":...}\n\n")
          .append("约束：命中项只给 file:line + 一句话，不贴代码；整份报告控制在 ")
          .append(MAX_REPORT_CHARS).append(" 字符内。");
        return sb.toString();
    }

    /**
     * 对子 Agent 返回的报告施加字符硬上限：未超限原样返回；超限则截断并追加 partial 标记,
     * 保证最终字符串长度不超过 {@link #MAX_REPORT_CHARS}。
     */
    public static String clampReport(String report) {
        if (report == null) {
            return "";
        }
        if (report.length() <= MAX_REPORT_CHARS) {
            return report;
        }
        int budget = Math.max(0, MAX_REPORT_CHARS - TRUNCATION_MARKER.length());
        return report.substring(0, budget) + TRUNCATION_MARKER;
    }
}