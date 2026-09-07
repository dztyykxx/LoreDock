package io.github.loredock.eval;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskConversationMapper;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.context.ContextReceipt;
import io.github.loredock.agent.model.context.ContextSummaryState;
import io.github.loredock.agent.model.context.ConversationContext;
import io.github.loredock.agent.model.entity.KnowledgeTaskConversationEntity;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.model.enums.AgentNode;
import io.github.loredock.agent.model.enums.ContextPurpose;
import io.github.loredock.agent.model.request.ContextAssemblyRequest;
import io.github.loredock.agent.service.ContextAssemblyService;
import io.github.loredock.agent.service.ContextCompressionService;
import io.github.loredock.agent.service.ContextTokenEstimator;
import io.github.loredock.agent.service.KnowledgeCurationGraphFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** 轻量模块评估运行器：仅以 Mapper 替身供应固定历史，真实调用组装、压缩和续做逻辑。 */
final class ContextCompressionEvalRunner {
    static final int OUTPUT_LIMIT = 8192;
    static final String SYSTEM = """
            你是知识整理助手。依据提供的会话和当前状态完成本轮指令，给出具体可执行的答复。
            本次没有工具可供调用，不要声称已经执行写入或发布。不要补造没有证据的业务事实。
            """;
    private final ObjectMapper mapper;
    private final ContextTokenEstimator estimator = new ContextTokenEstimator();

    ContextCompressionEvalRunner(ObjectMapper mapper) { this.mapper = mapper; }

    record MessageText(String role, String text) { }

    /** 估算量与供应商 usage 分开；未返回 usage 时使用 null，不伪装为零。 */
    record Call(List<MessageText> input, String output, int estimatedInputTokens, Integer actualInputTokens,
                Integer actualOutputTokens, String responseModel, String finishReason, long elapsedMillis, String error) { }

    record Variant(Call call, List<String> errors, ContextCompressionEvalJudge.Judgement judgement) {
        boolean passed() {
            return call != null && call.error() == null && errors.isEmpty() && judgement != null && judgement.passed();
        }
    }

    record CaseResult(ContextCompressionEvalFixture.Case sample, int repetition, String mode,
                      ContextReceipt receipt, List<Call> compressionCalls, Map<String, Object> summaryState,
                      Variant full, Variant compressed) { }

    /** 报告保存数据快照与版本，重判读取此快照而不是读取可能已经修改的样例文件。 */
    record Report(String createdAt, ContextCompressionEvalFixture.Dataset dataset, String model, double temperature,
                  int outputLimit, String graphVersion, String systemPrompt, String estimatorMode,
                  String judgeModel, String judgePromptVersion, String judgeInstruction, List<CaseResult> results) { }

    /** 每次从空摘要和独立会话标识开始；完整组直接插入全部历史，避开生产入口的八轮窗口。 */
    CaseResult run(ContextCompressionEvalFixture.Case sample, ContextBudget budget, ChatModel model, int repetition) {
        System.out.println("评估用户原始问题：" + sample.currentInstruction());
        long id = 100_000L * repetition + Integer.parseInt(sample.id().substring(1));
        var conversations = mock(KnowledgeTaskConversationMapper.class);
        var messages = mock(KnowledgeTaskMessageMapper.class);
        when(conversations.selectOne(any(Wrapper.class))).thenReturn(KnowledgeTaskConversationEntity.builder()
                .id(id).targetSkill("knowledge-curator").build());
        List<KnowledgeTaskMessageEntity> rows = new ArrayList<>();
        for (int i = 0; i < sample.history().size(); i++) {
            var history = sample.history().get(i);
            rows.add(KnowledgeTaskMessageEntity.builder().id((long) i + 1).conversationId(id).runId(id)
                    .createdAt(Instant.EPOCH.plusSeconds(i)).subjectName("knowledge-curator")
                    .role("USER".equals(history.role()) ? "USER" : "COORDINATOR_AGENT").content(history.text()).build());
        }
        when(messages.selectList(any(Wrapper.class))).thenReturn(rows);
        var compression = new ContextCompressionService(mapper, messages, estimator);
        var assembler = new ContextAssemblyService(conversations, messages, budget, estimator, compression);
        var empty = new ContextSummaryState(null, 0, null, null, 0);

        // 先让同一真实组装器生成无历史的前后缀，完整组只在历史区间插入全部原文；绝不拼入 checks。
        var shell = assembler.assemble(request(sample, budget, id, List.of()), empty, 0, model).prepared();
        List<Message> fullInput = new ArrayList<>();
        fullInput.add(new SystemMessage(SYSTEM));
        boolean inserted = false;
        for (Message message : shell.messages()) {
            if (!inserted && message.getText().startsWith("【当前指令】")) {
                sample.history().forEach(history -> fullInput.add("USER".equals(history.role())
                        ? new UserMessage(history.text()) : new AssistantMessage(history.text())));
                inserted = true;
            }
            fullInput.add(message);
        }
        if (!inserted) {
            throw new IllegalStateException("生产组装消息布局发生变化，无法定位完整历史插入位置");
        }
        Variant full = continuation(model, fullInput, List.of());
        List<Call> compressionCalls = new ArrayList<>();
        ChatModel recordedCompressor = prompt -> {
            var response = new java.util.concurrent.atomic.AtomicReference<ChatResponse>();
            Call actual = invoke(input -> {
                ChatResponse result = model.call(input);
                response.set(result);
                return result;
            }, prompt);
            compressionCalls.add(actual);
            if (actual.error() != null) {
                throw new IllegalStateException("COMPRESSION_MODEL_ERROR:" + actual.error());
            }
            return response.get();
        };
        var history = sample.history().stream()
                .map(message -> new ConversationContext.DialogueTurn(message.role(), message.text())).toList();
        var assembled = assembler.assemble(request(sample, budget, id, history), empty, 0, recordedCompressor);
        String mode = assembled.prepared().receipt().mode().name();
        List<String> errors = new ArrayList<>();
        if (!"LLM_COMPRESSED".equals(mode)) errors.add("NOT_COMPRESSED");
        if (compressionCalls.size() != 1) errors.add("COMPRESSION_CALL_COUNT");
        Variant compressed;
        if ("BLOCKED".equals(mode)) {
            errors.add("ASSEMBLY_BLOCKED");
            compressed = new Variant(null, List.copyOf(errors), null);
        } else {
            List<Message> input = new ArrayList<>();
            input.add(new SystemMessage(SYSTEM));
            input.addAll(assembled.prepared().messages());
            int tokens = estimator.estimate(input).tokens();
            if (tokens >= full.call().estimatedInputTokens()) errors.add("INPUT_NOT_REDUCED");
            if (tokens > budget.maxInputTokens()) errors.add("INPUT_OVER_BUDGET_WITH_SYSTEM");
            compressed = continuation(model, input, errors);
        }
        System.out.printf("评估证据：case=%s repetition=%d mode=%s compressionCalls=%d errors=%s%n",
                sample.id(), repetition, mode, compressionCalls.size(), compressed.errors());
        return new CaseResult(sample, repetition, mode, assembled.prepared().receipt(), List.copyOf(compressionCalls),
                assembled.stateUpdates(), full, compressed);
    }

    private ContextAssemblyRequest request(ContextCompressionEvalFixture.Case sample, ContextBudget budget, long id,
                                           List<ConversationContext.DialogueTurn> history) {
        return new ContextAssemblyRequest(id, id, AgentNode.MAIN_AGENT, ContextPurpose.CHAT, sample.currentInstruction(),
                new ConversationContext(sample.originalGoal(), history, List.of(), null, false), sample.workflowState(), budget, null);
    }

    private Variant continuation(ChatModel model, List<Message> messages, List<String> errors) {
        var call = invoke(model, new Prompt(messages));
        List<String> actualErrors = new ArrayList<>(errors);
        if (call.error() != null) actualErrors.add("CONTINUATION_MODEL_ERROR");
        System.out.println("续做模型原始响应：" + call.output());
        return new Variant(call, List.copyOf(actualErrors), null);
    }

    private Call invoke(ChatModel model, Prompt prompt) {
        long start = System.nanoTime();
        var input = prompt.getInstructions().stream().map(message -> new MessageText(message.getMessageType().name(), message.getText())).toList();
        int tokens = estimator.estimate(prompt.getInstructions()).tokens();
        try {
            ChatResponse response = model.call(prompt);
            String output = response.getResult().getOutput().getText();
            String finishReason = response.getResult().getMetadata().getFinishReason();
            String error = "length".equalsIgnoreCase(finishReason) ? "OUTPUT_TRUNCATED"
                    : output == null || output.isBlank() ? "EMPTY_MODEL_OUTPUT" : null;
            var usage = response.getMetadata().getUsage();
            boolean hasUsage = usage != null && !(usage instanceof EmptyUsage);
            return new Call(input, output, tokens, hasUsage ? usage.getPromptTokens() : null,
                    hasUsage ? usage.getCompletionTokens() : null, response.getMetadata().getModel(),
                    finishReason, (System.nanoTime() - start) / 1_000_000, error);
        } catch (RuntimeException exception) {
            return new Call(input, null, tokens, null, null, null, null, (System.nanoTime() - start) / 1_000_000,
                    ContextCompressionEvalJudge.failureCode(exception));
        }
    }

    /** 本版统一温度零、输出上限 8192（包含供应商可能使用的推理 Token），不修改生产模型配置。 */
    Report report(ContextCompressionEvalFixture.Dataset data, String model, List<CaseResult> results) {
        return new Report(Instant.now().toString(), data, model, 0, OUTPUT_LIMIT, KnowledgeCurationGraphFactory.GRAPH_DEF_VERSION,
                SYSTEM, ContextTokenEstimator.UTF8_BYTE_BOUND, null, null, null, List.copyOf(results));
    }

    /** 离线重判全部已有回答，逐条持久化进度；同一文件所有判定使用一个 Judge 配置。 */
    Report judge(Report raw, ChatModel model, String modelName, Path output) throws Exception {
        var judge = new ContextCompressionEvalJudge(mapper);
        List<CaseResult> results = new ArrayList<>();
        Report current = judgedReport(raw, modelName, results);
        for (CaseResult item : raw.results()) {
            // 交替调用顺序，但每次只发送一个匿名回答；防止裁判看见分组和另一答案。
            Variant full;
            Variant compressed;
            if (Integer.parseInt(item.sample().id().substring(1)) % 2 == 0) {
                compressed = judgedVariant(item.sample(), item.compressed(), model, judge);
                full = judgedVariant(item.sample(), item.full(), model, judge);
            } else {
                full = judgedVariant(item.sample(), item.full(), model, judge);
                compressed = judgedVariant(item.sample(), item.compressed(), model, judge);
            }
            results.add(new CaseResult(item.sample(), item.repetition(), item.mode(), item.receipt(), item.compressionCalls(),
                    item.summaryState(), full, compressed));
            current = judgedReport(raw, modelName, results);
            write(current, output);
            System.out.printf("Judge证据：case=%s full=%s compressed=%s%n", item.sample().id(), status(full), status(compressed));
        }
        return current;
    }

    private Variant judgedVariant(ContextCompressionEvalFixture.Case sample, Variant variant, ChatModel model,
                                   ContextCompressionEvalJudge judge) {
        if (variant.call() == null || variant.call().error() != null) return new Variant(variant.call(), variant.errors(), null);
        return new Variant(variant.call(), variant.errors(), judge.judge(sample, variant.call().output(), model));
    }

    private Report judgedReport(Report raw, String judgeModel, List<CaseResult> results) {
        return new Report(raw.createdAt(), raw.dataset(), raw.model(), raw.temperature(), raw.outputLimit(), raw.graphVersion(),
                raw.systemPrompt(), raw.estimatorMode(), judgeModel, ContextCompressionEvalJudge.PROMPT_VERSION,
                ContextCompressionEvalJudge.INSTRUCTION, List.copyOf(results));
    }

    /** 原始 JSON 用于重判和核验；Markdown 只提供两项指标和逐项失败原因。 */
    void write(Report report, Path path) throws Exception {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(absolute.toFile(), report);
        String filename = absolute.getFileName().toString().replaceFirst("\\.json$", "") + ".md";
        Files.writeString(absolute.resolveSibling(filename), markdown(report));
    }

    Report read(Path path) throws Exception { return mapper.readValue(path.toFile(), Report.class); }

    private String markdown(Report report) {
        long fullPassed = report.results().stream().filter(item -> item.full().passed()).count();
        long compressedPassed = report.results().stream().filter(item -> item.compressed().passed()).count();
        StringBuilder text = new StringBuilder("# 上下文压缩轻量评估报告\n\n");
        text.append("数据版本：").append(report.dataset().version()).append("；模型：").append(report.model())
                .append("；Judge：").append(report.judgeModel()).append("；温度：").append(report.temperature()).append("。\n\n")
                .append("已记录运行数：").append(report.results().size()).append("；快照中的独立样例数：")
                .append(report.dataset().cases().size()).append("。重复运行不是独立样本；未跑齐时不能当成完整评估。\n\n")
                .append("当前已记录运行通过数（未判分/技术失败/JUDGE_ERROR 均不算通过）：完整组 ")
                .append(fullPassed).append('/').append(report.results().size()).append("；压缩组 ")
                .append(compressedPassed).append('/').append(report.results().size()).append("。\n\n")
                .append("Token 降幅 = 1 − 压缩组完整输入估算量 / 完整组完整输入估算量，含系统提示，不含压缩和 Judge 成本；估算模式：")
                .append(report.estimatorMode()).append("。供应商 usage 单独保存在 JSON。\n\n")
                .append("| 样例/轮次 | 保护来源 | 模式/压缩调用 | 输入估算(完整→压缩) | 降幅 | 完整组 | 压缩组 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        for (CaseResult item : report.results()) {
            var before = item.full().call();
            var after = item.compressed().call();
            String reduction = before != null && after != null
                    ? String.format(Locale.ROOT, "%.1f%%", 100.0 * (1 - (double) after.estimatedInputTokens() / before.estimatedInputTokens())) : "N/A";
            text.append("| ").append(item.sample().id()).append('/').append(item.repetition()).append(" | ")
                    .append(item.sample().protectionSource()).append(" | ").append(item.mode()).append('/').append(item.compressionCalls().size())
                    .append(" | ").append(before == null ? "N/A" : before.estimatedInputTokens()).append(" → ")
                    .append(after == null ? "N/A" : after.estimatedInputTokens()).append(" | ").append(reduction)
                    .append(" | ").append(status(item.full())).append(" | ").append(status(item.compressed())).append(" |\n");
        }
        text.append("\n## 失败定位\n\n");
        for (CaseResult item : report.results()) {
            appendFailures(text, item, "完整", item.full());
            appendFailures(text, item, "压缩", item.compressed());
        }
        text.append("\n范围：一次真实组装/压缩后的文本续做，不证明滚动摘要、多轮工具执行、数据库或 UI 端到端。人工仍需校准裁判并抽查失败项和至少两条通过项。\n");
        return text.toString();
    }

    private static String status(Variant variant) {
        if (!variant.errors().isEmpty()) return String.join(",", variant.errors());
        if (variant.judgement() == null) return "UNJUDGED";
        if (!"JUDGED".equals(variant.judgement().status())) return variant.judgement().status();
        return variant.passed() ? "PASS" : "FAIL";
    }

    private static void appendFailures(StringBuilder text, CaseResult item, String group, Variant variant) {
        String label = item.sample().id() + "/" + item.repetition() + " " + group;
        if (!variant.errors().isEmpty()) text.append("- ").append(label).append("：").append(variant.errors()).append('\n');
        if (variant.call() != null && variant.call().error() != null) {
            text.append("- ").append(label).append(" 请求错误：").append(variant.call().error())
                    .append("；finishReason=").append(variant.call().finishReason()).append('\n');
        }
        if (variant.judgement() == null) return;
        var judgement = variant.judgement();
        if (judgement.error() != null) text.append("- ").append(label).append("：JUDGE_ERROR ").append(judgement.error()).append('\n');
        judgement.checks().stream().filter(check -> "FAIL".equals(check.verdict())).forEach(check ->
                text.append("- ").append(label).append(" / ").append(check.id()).append("：").append(check.reason()).append('\n'));
    }
}
