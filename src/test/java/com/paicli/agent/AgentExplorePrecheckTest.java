package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 23.5 确定性预检单测：构造连续检索超阈值场景，断言注入了建议提示。
 */
class AgentExplorePrecheckTest {

    private Agent agent;
    private StubClient llm;
    private ToolRegistry registry;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        llm = new StubClient();
        registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        agent = new Agent(llm, registry);
    }

    @Test
    void injectsSuggestionWhenConsecutiveSearchCallsExceedThreshold() {
        int threshold = Agent.EXPLORE_PRECHECK_THRESHOLD;
        for (int i = 0; i < threshold; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code", "some result")),
                    "找到所有 Service 的注册链路");
        }

        assertTrue(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "连续检索超阈值后应注入 explore_codebase 建议");
    }

    @Test
    void noSuggestionWhenBelowThreshold() {
        for (int i = 0; i < Agent.EXPLORE_PRECHECK_THRESHOLD - 1; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code", "some result")),
                    "找到 Service 注册");
        }

        assertFalse(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "未超阈值时不应注入建议");
    }

    @Test
    void nonSearchToolCallResetsCounter() {
        for (int i = 0; i < Agent.EXPLORE_PRECHECK_THRESHOLD - 1; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code", "some result")),
                    "找代码");
        }

        // 中间插入一个非检索工具调用，计数器应重置
        agent.maybeInjectExploreSuggestion(
                List.of(writeToolCall("write-1")),
                List.of(toolResult("write-1", "write_file", "ok")),
                "找代码");

        // 再来几次检索，总数未超阈值
        for (int i = 0; i < 2; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("after-" + i)),
                    List.of(toolResult("after-" + i, "grep_code", "some result")),
                    "找代码");
        }

        assertFalse(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "非检索工具调用应重置计数器");
    }

    @Test
    void partialResultTriggersEarlierSuggestion() {
        // partial: true 时阈值降低到 3
        for (int i = 0; i < 3; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code",
                            "结果过多... partial: true, suggested_reads: [...]")),
                    "找所有调用链");
        }

        assertTrue(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")
                                && m.content().contains("partial")),
                "含 partial 截断时应更早触发建议");
    }

    @Test
    void skipsSuggestionWhenUserExplicitlyOptOut() {
        for (int i = 0; i < Agent.EXPLORE_PRECHECK_THRESHOLD + 2; i++) {
            agent.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code", "some result")),
                    "自己查就行，不要派发");
        }

        assertFalse(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "用户明确说不要派发时不应注入建议");
    }

    @Test
    void skipsSuggestionWhenExploreToolNotAvailable(@TempDir Path tempDir) {
        ToolRegistry noExploreTool = new ToolRegistry();
        noExploreTool.setProjectPath(tempDir.toString());
        // 不调 setExploreRunner，但手动裁剪掉 explore_codebase
        ToolRegistry view = noExploreTool.readOnlyExploreView();
        Agent agentNoExplore = new Agent(llm, view);

        for (int i = 0; i < Agent.EXPLORE_PRECHECK_THRESHOLD + 2; i++) {
            agentNoExplore.maybeInjectExploreSuggestion(
                    List.of(grepToolCall("call-" + i)),
                    List.of(toolResult("call-" + i, "grep_code", "some result")),
                    "找代码");
        }

        assertFalse(agentNoExplore.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "explore_codebase 工具不可用时不应注入建议");
    }

    @Test
    void parallelSearchCallsCountAllInOneBatch() {
        // 一轮并行返回 3 个 grep_code
        List<LlmClient.ToolCall> batch = List.of(
                grepToolCall("a"), grepToolCall("b"), grepToolCall("c"));
        List<ToolExecutionResult> results = List.of(
                toolResult("a", "grep_code", "r1"),
                toolResult("b", "grep_code", "r2"),
                toolResult("c", "grep_code", "r3"));

        // 第一轮 3 个
        agent.maybeInjectExploreSuggestion(batch, results, "查链路");
        // 第二轮 3 个，累计 6 = 阈值
        agent.maybeInjectExploreSuggestion(batch, results, "查链路");

        assertTrue(agent.getConversationHistory().stream()
                        .anyMatch(m -> "user".equals(m.role())
                                && m.content().contains("explore_codebase")),
                "并行检索调用应累加计入连续检索计数");
    }

    // --- helpers ---

    private static LlmClient.ToolCall grepToolCall(String id) {
        return new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("grep_code", "{\"pattern\":\"test\"}"));
    }

    private static LlmClient.ToolCall writeToolCall(String id) {
        return new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("write_file", "{\"path\":\"x.txt\",\"content\":\"hi\"}"));
    }

    private static ToolExecutionResult toolResult(String id, String name, String result) {
        return new ToolExecutionResult(id, name, "{}", result, 10L, false, List.of());
    }

    private static final class StubClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return new ChatResponse("assistant", "ok", null, 10, 5);
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