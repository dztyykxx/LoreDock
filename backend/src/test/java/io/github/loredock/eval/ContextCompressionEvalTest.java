package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** 保护评估的真实输入范围、分组隔离与判分有效性，不将脚本模型结果当成质量测量。 */
class ContextCompressionEvalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;

    /** 业务目的：六条样例均实际进入压缩分支；完整组不能偷偷裁剪，预期答案不能泄漏给被测模型。 */
    @Test
    void pairedRunsUseRealCompressionAndKeepGoldChecksOutOfModelInputs() throws Exception {
        var data = ContextCompressionEvalFixture.load(mapper);
        List<Prompt> prompts = new ArrayList<>();
        ChatModel model = scripted(prompts, prompt -> isCompressor(prompt) ? summary() : "下一步仍需按原要求处理。");
        var runner = new ContextCompressionEvalRunner(mapper);
        for (var sample : data.cases()) {
            var actual = runner.run(sample, data.budget(), model, 1);
            assertThat(actual.mode()).isEqualTo("LLM_COMPRESSED");
            assertThat(actual.compressionCalls()).hasSize(1);
            assertThat(actual.compressed().errors()).isEmpty();
            assertThat(actual.compressed().call().estimatedInputTokens()).isLessThan(actual.full().call().estimatedInputTokens());
            for (var history : sample.history()) {
                assertThat(actual.full().call().input()).anyMatch(message -> message.text().equals(history.text()));
            }
            String fullText = mapper.writeValueAsString(actual.full().call().input());
            assertThat(fullText).doesNotContain("protectionSource", "sourceIds", "requirement");
            assertThat(actual.full().call().actualInputTokens()).isNull();
            assertThat(actual.summaryState()).containsKey("conversationSummary");
            System.out.printf("测试证据：样例=%s，完整输入=%d，压缩输入=%d，压缩调用=%d，mode=%s%n",
                    sample.id(), actual.full().call().estimatedInputTokens(), actual.compressed().call().estimatedInputTokens(),
                    actual.compressionCalls().size(), actual.mode());
        }
        assertThat(prompts).hasSize(18);
    }

    /** 业务目的：压缩前截断导致的约束丢失必须能从采集输入定位，不能让测试替身替压缩器补上答案。 */
    @Test
    void capturesPreSummaryTruncationAndRejectsUnknownReferenceWithoutContinuation() throws Exception {
        var data = ContextCompressionEvalFixture.load(mapper);
        var runner = new ContextCompressionEvalRunner(mapper);
        var actual = runner.run(data.cases().get(1), data.budget(),
                scripted(new ArrayList<>(), prompt -> isCompressor(prompt) ? summary() : "继续"), 1);
        String source = data.cases().get(1).history().getFirst().text();
        String compressorInput = actual.compressionCalls().getFirst().input().getFirst().text();
        assertThat(source).contains("必须保留人工审核步骤");
        assertThat(compressorInput).doesNotContain("必须保留人工审核步骤");
        var invalid = runner.run(data.cases().getFirst(), data.budget(),
                scripted(new ArrayList<>(), prompt -> isCompressor(prompt)
                        ? "{\"summary\":\"继续\",\"retainedReferenceIds\":[\"KNOWLEDGE:999\"]}" : "继续"), 1);
        assertThat(invalid.mode()).isEqualTo("BLOCKED");
        assertThat(invalid.compressed().call()).isNull();
        assertThat(invalid.compressed().errors()).contains("ASSEMBLY_BLOCKED");
        System.out.printf("测试证据：长消息约束在摘要前丢失=%s，非法引用mode=%s，续做请求未发送=%s%n",
                !compressorInput.contains("必须保留人工审核步骤"), invalid.mode(), invalid.compressed().call() == null);
    }

    /** 业务目的：Judge 必须匿名逐项评分，缺项、重复和无依据引用不得冒充通过。 */
    @Test
    void judgeRequiresCompleteChecksAndVerifiableEvidence() throws Exception {
        var sample = ContextCompressionEvalFixture.load(mapper).cases().get(2);
        var judge = new ContextCompressionEvalJudge(mapper);
        List<Prompt> prompts = new ArrayList<>();
        String answer = "最终放开发指南，旧目录不再采用。";
        String valid = judgement(sample, answer);
        var result = judge.judge(sample, answer, scripted(prompts, ignored -> valid));
        assertThat(result.status()).isEqualTo("JUDGED");
        assertThat(result.passed()).isTrue();
        assertThat(prompts.getFirst().getContents()).doesNotContain("LLM_COMPRESSED", "compressionRatio", "protectionSource");
        assertThat(judge.judge(sample, answer, scripted(new ArrayList<>(), ignored -> "{\"checks\":[]}")).status())
                .isEqualTo("JUDGE_ERROR");
        assertThat(judge.judge(sample, answer, scripted(new ArrayList<>(), ignored -> valid.replace("最终放开发指南，旧目录不再采用。", "不存在的回答证据"))).status())
                .isEqualTo("JUDGE_ERROR");
        var duplicate = mapper.readTree(valid).deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.get("checks")).add(duplicate.get("checks").get(0));
        assertThat(judge.judge(sample, answer, scripted(new ArrayList<>(), ignored -> duplicate.toString())).status())
                .isEqualTo("JUDGE_ERROR");
        System.out.printf("测试证据：合法Judge通过=%s，完整要求数=%d，缺项与虚构证据均拒绝%n", result.passed(), result.checks().size());
    }

    /** 业务目的：模型网络异常不能丢弃样例或冒充业务失败原因，必须保留输入并显示执行错误。 */
    @Test
    void modelFailureKeepsEvidenceAndCannotPass() throws Exception {
        var data = ContextCompressionEvalFixture.load(mapper);
        ChatModel unavailable = scripted(new ArrayList<>(), ignored -> { throw new IllegalStateException("test failure"); });
        var actual = new ContextCompressionEvalRunner(mapper).run(data.cases().getFirst(), data.budget(), unavailable, 1);
        assertThat(actual.full().errors()).contains("CONTINUATION_MODEL_ERROR");
        assertThat(actual.full().call().input()).isNotEmpty();
        assertThat(actual.full().call().actualInputTokens()).isNull();
        assertThat(actual.compressionCalls()).hasSize(1);
        assertThat(actual.compressionCalls().getFirst().error()).isEqualTo("IllegalStateException");
        assertThat(actual.compressed().errors()).contains("ASSEMBLY_BLOCKED");
        assertThat(actual.full().passed()).isFalse();
        assertThat(new ContextCompressionEvalJudge(mapper).judge(data.cases().getFirst(), "继续", unavailable).status())
                .isEqualTo("JUDGE_ERROR");
        System.out.printf("测试证据：完整组错误=%s，压缩错误=%s，压缩请求记录=%d，模型失败不计通过%n",
                actual.full().errors(), actual.compressed().errors(), actual.compressionCalls().size());
    }

    /** 业务目的：推理耗尽输出预算时可能没有正文，仍必须保存实际 usage 和结束原因，便于校准而非误判压缩能力。 */
    @Test
    void truncatedResponseRetainsUsageAndFinishReason() throws Exception {
        var data = ContextCompressionEvalFixture.load(mapper);
        ChatModel exhausted = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(""),
                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder().finishReason("length").build())),
                org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(100, 2048)).model("scripted").build());
        var actual = new ContextCompressionEvalRunner(mapper).run(data.cases().getFirst(), data.budget(), exhausted, 1);
        assertThat(actual.full().call().error()).isEqualTo("OUTPUT_TRUNCATED");
        assertThat(actual.full().call().finishReason()).isEqualTo("length");
        assertThat(actual.full().call().actualInputTokens()).isEqualTo(100);
        assertThat(actual.full().call().actualOutputTokens()).isEqualTo(2048);
        assertThat(actual.compressed().passed()).isFalse();
        System.out.printf("测试证据：空正文错误=%s，finishReason=%s，保留actualOutputTokens=%d%n",
                actual.full().call().error(), actual.full().call().finishReason(), actual.full().call().actualOutputTokens());
    }

    /** 业务目的：离线重判必须只调用裁判，报告可重读；Judge 通过不得掩盖未压缩或技术失败。 */
    @Test
    void savedReportCanBeRejudgedWithoutCallingBusinessModel() throws Exception {
        var data = ContextCompressionEvalFixture.load(mapper);
        var runner = new ContextCompressionEvalRunner(mapper);
        var actual = runner.run(data.cases().get(2), data.budget(),
                scripted(new ArrayList<>(), prompt -> isCompressor(prompt) ? summary() : "最终放开发指南。"), 1);
        var report = runner.report(data, "scripted", List.of(actual));
        Path output = directory.resolve("report.json");
        runner.write(report, output);
        var saved = runner.read(output);
        List<Prompt> judgeCalls = new ArrayList<>();
        var judged = runner.judge(saved, scripted(judgeCalls,
                ignored -> judgement(data.cases().get(2), "最终放开发指南。")), "scripted-judge", output);
        assertThat(judgeCalls).hasSize(2);
        assertThat(judged.results().getFirst().compressed().passed()).isTrue();
        assertThat(Files.readString(directory.resolve("report.md"))).contains("1/1");
        var failed = new ContextCompressionEvalRunner.Variant(actual.compressed().call(), List.of("NOT_COMPRESSED"),
                judged.results().getFirst().compressed().judgement());
        assertThat(failed.passed()).isFalse();
        System.out.printf("测试证据：报告往返样例=%d，离线Judge调用=%d，技术失败不可被Judge覆盖=%s%n",
                saved.results().size(), judgeCalls.size(), !failed.passed());
    }

    private static String summary() {
        return "{\"summary\":\"前序讨论的任务要求继续有效。\",\"retainedReferenceIds\":[],\"retainedDecisionIds\":[],\"retainedQuestionIds\":[]}";
    }

    private String judgement(ContextCompressionEvalFixture.Case sample, String answer) {
        try {
            var checks = sample.checks().stream().map(check -> new ContextCompressionEvalJudge.CheckResult(
                    check.id(), "PASS", List.of("m3"), "最终改放开发指南", answer, "与用户更正一致")).toList();
            return mapper.writeValueAsString(java.util.Map.of("checks", checks));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean isCompressor(Prompt prompt) {
        return prompt.getContents().contains("你是知识整理会话的上下文压缩器");
    }

    private static ChatModel scripted(List<Prompt> prompts, java.util.function.Function<Prompt, String> output) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(output.apply(prompt)))));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
    }
}
