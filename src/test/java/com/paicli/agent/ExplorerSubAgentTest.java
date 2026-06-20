package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplorerSubAgentTest {

    @Test
    void readOnlyViewExposesOnlyReadOnlySearchTools(@TempDir Path tempDir) {
        ToolRegistry main = new ToolRegistry();
        main.setProjectPath(tempDir.toString());

        ToolRegistry view = main.readOnlyExploreView();

        for (String tool : List.of("glob_files", "grep_code", "read_file", "list_dir", "search_code")) {
            assertTrue(view.hasTool(tool), "只读视图应保留只读检索工具 " + tool);
        }
        for (String tool : List.of("write_file", "execute_command", "create_project", "revert_turn", "explore_codebase")) {
            assertFalse(view.hasTool(tool), "只读视图不应包含 " + tool);
        }
    }

    @Test
    void readOnlyViewRejectsWriteToolInvocation() {
        ToolRegistry view = new ToolRegistry().readOnlyExploreView();

        String result = view.executeTool("write_file", "{\"path\":\"x.txt\",\"content\":\"hi\"}");

        assertTrue(result.contains("未知工具"), result);
    }

    @Test
    void readOnlyViewInheritsMainProjectPathNotJvmStartDir(@TempDir Path tempDir) {
        ToolRegistry main = new ToolRegistry();
        main.setProjectPath(tempDir.toString());

        ToolRegistry view = main.readOnlyExploreView();

        // 关键回归：视图必须沿用主 Agent 运行期的 projectPath（可能被 /index 改过），
        // 而不是 new ToolRegistry() 退回的 user.dir，否则会搜错目录。
        assertEquals(tempDir.toString(), view.getProjectPath());
    }

    @Test
    void buildPromptEnforcesThreeSectionContract() {
        String prompt = ExploreContract.buildPrompt(
                "找到 grep_code 工具的注册与执行链路", "src/main/java/com/paicli/tool");

        assertTrue(prompt.contains("找到 grep_code 工具的注册与执行链路"), prompt);
        assertTrue(prompt.contains("src/main/java/com/paicli/tool"), prompt);
        assertTrue(prompt.contains("## 结论"), prompt);
        assertTrue(prompt.contains("## 命中"), prompt);
        assertTrue(prompt.contains("## 建议下一步读取"), prompt);
        assertTrue(prompt.contains(String.valueOf(ExploreContract.MAX_REPORT_CHARS)), prompt);
    }

    @Test
    void buildPromptOmitsFocusLineWhenBlank() {
        String prompt = ExploreContract.buildPrompt("定位入口", "  ");

        assertFalse(prompt.contains("搜索范围限定"), prompt);
        assertTrue(prompt.contains("定位入口"), prompt);
    }

    @Test
    void clampReportLeavesShortReportUntouched() {
        String report = "## 结论\n已定位\n\n## 命中\n- A.java:1 — x\n\n## 建议下一步读取\n- read_file {}";

        assertEquals(report, ExploreContract.clampReport(report));
    }

    @Test
    void clampReportTruncatesOverLimitAndMarksPartial() {
        String oversized = "x".repeat(ExploreContract.MAX_REPORT_CHARS + 500);

        String clamped = ExploreContract.clampReport(oversized);

        assertTrue(clamped.length() <= ExploreContract.MAX_REPORT_CHARS,
                "截断后长度应不超过上限，实际=" + clamped.length());
        assertTrue(clamped.contains("partial: true"), clamped);
    }

    @Test
    void agentWiresExploreRunnerThatRunsReadOnlySubAgent(@TempDir Path tempDir) {
        RecordingClient llm = new RecordingClient(List.of(
                new LlmClient.ChatResponse("assistant",
                        "## 结论\n已定位 grep_code 注册。\n\n## 命中\n- ToolRegistry.java:332 — grep_code 注册\n\n## 建议下一步读取\n- read_file {\"path\":\"ToolRegistry.java\"}",
                        null, 30, 20)
        ));
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        new Agent(llm, registry);   // 构造即装配 setExploreRunner

        String report = registry.executeTool("explore_codebase",
                "{\"task\":\"grep_code 注册链路\",\"focus_paths\":\"src\"}");

        // 报告由子 Agent 产出并经契约裁剪后回传
        assertTrue(report.contains("## 结论"), report);
        assertTrue(report.contains("ToolRegistry.java:332"), report);
        // 子 Agent 确实拉起了一次 LLM 会话
        assertEquals(1, llm.messagesByCall.size());
        // 子 Agent 收到的任务提示词含三段输出契约
        assertTrue(llm.messagesByCall.get(0).stream()
                        .anyMatch(m -> m.content() != null && m.content().contains("## 建议下一步读取")),
                "子 Agent 应收到含三段契约的任务提示词");
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> messagesByCall = new ArrayList<>();

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            messagesByCall.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        @Override
        public String getModelName() {
            return "test";
        }

        @Override
        public String getProviderName() {
            return "test";
        }
    }
}