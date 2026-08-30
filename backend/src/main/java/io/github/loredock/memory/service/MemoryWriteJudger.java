package io.github.loredock.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.api.MemoryWriteOutcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * memory_write 语义判断器：单次受控 ChatModel 调用，为每个候选判断
 * CREATED / CONFLICT_CREATED / SKIP_DUPLICATE / SKIP_NOT_WORTH。
 *
 * <p>与 {@code ContextCompressionService} 同先例（内部受限调用、非 Agent、无 Spec/Tool），
 * 失败以 {@link IllegalStateException} 上报调用方（可整体重试），不产生无判断记录。</p>
 *
 * <p>模型通过 {@link ObjectProvider} 延迟解析：部署未显式启用模型（spring.ai.model.chat=none）时
 * 应用照常启动（记忆检索/预载不依赖模型），只有真正执行写入判断时才以明确错误失败。</p>
 */
public class MemoryWriteJudger {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteJudger.class);

    private static final String MODEL_UNAVAILABLE =
            "平台未配置 ChatModel（spring.ai.model.chat 未启用 openai），记忆写入判断不可用";

    private final ObjectProvider<ChatModel> model;
    private final ObjectMapper objectMapper;

    public MemoryWriteJudger(ObjectProvider<ChatModel> model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /** 供给判断器的一条候选（编号独立于原始下标，避免参差）。 */
    public record CandidateForJudgement(
            int slot,
            MemoryCategory category,
            String title,
            String content
    ) {
    }

    /** 供给判断器的既有记忆视图：只含判断所需字段，不暴露正文全文。 */
    public record ExistingMemories(
            Long id,
            MemoryCategory category,
            MemoryStatus status,
            String title,
            String summary
    ) {
    }

    /** 单候选判断结论。 */
    public record Judgement(
            int slot,
            MemoryWriteOutcome outcome,
            List<Long> conflictsWith,
            String summary
    ) {
    }

    /**
     * 批量判断：模型无有效输出、JSON 非法或结论编号越界/引用不存在 -> {@link IllegalStateException}。
     *
     * @param candidates 候选（每条限长已由调用方校验）
     * @param existing 同范围相近既有记忆（ACTIVE + DISABLED，上限调用方控制）
     */
    public List<Judgement> judge(List<CandidateForJudgement> candidates, List<ExistingMemories> existing) {
        String prompt = buildPrompt(candidates, existing);
        log.info("记忆写入判断 agent=memory_write_judger candidates={} 既有记忆={}", candidates.size(), existing.size());
        ChatModel chatModel = model.getIfAvailable();
        if (chatModel == null) {
            throw new IllegalStateException(MODEL_UNAVAILABLE);
        }
        ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("记忆写入判断无有效模型输出");
        }
        List<Judgement> result = parse(candidates, existing, text);
        log.info("记忆写入判断 agent=memory_write_judger 完成 结论={}",
                result.stream().map(item -> item.outcome() + ":" + item.slot()).toList());
        return result;
    }

    private String buildPrompt(List<CandidateForJudgement> candidates, List<ExistingMemories> existing) {
        String existingBlock = existing == null || existing.isEmpty()
                ? "（无既存记忆）"
                : existing.stream().map(item -> "#" + item.id() + " [" + item.status() + " " + item.category()
                        + "] " + bounded(item.title(), 60) + ": " + bounded(item.summary(), 120))
                .reduce((left, right) -> left + "\n" + right).orElse("");
        String candidateBlock = candidates.stream().map(item ->
                        "候选" + item.slot() + " 分类=" + item.category() + "\n标题：" + bounded(item.title(), 100)
                                + "\n内容：" + bounded(item.content(), 600))
                .reduce((left, right) -> left + "\n---\n" + right).orElse("");
        return "你是知识整理的「用户偏好记忆提炼判断器」，用户在与助手的对话中表达了对文档产出的偏好，"
                + "请逐条候选判断是否要作为长期记忆写入。\n"
                + "规则：\n"
                + "- 一次性任务指令（如“这次只先改标题”“本次用这个模板”）→ SKIP_NOT_WORTH；\n"
                + "- 与既存记忆表达同一偏好（同分类、语义相同、异词表达）→ SKIP_DUPLICATE，不得改动既存记忆；\n"
                + "- 与既存某条记忆语义冲突（同类目但内容相反/不一致，如“正式用语”vs“口语风格”）"
                + "→ CONFLICT_CREATED，候选仍须写入，冲突双方同时保留 ACTIVE；\n"
                + "- 其余 → CREATED。\n"
                + "只输出一个 JSON 数组，不得输出解释："
                + "[{\"candidateIndex\":0,\"verdict\":\"CREATED\",\"conflictsWith\":[既有编号],\"summary\":\"可选摘要≤300字\"}]\n"
                + "candidateIndex 对应候选编号；conflictsWith 只能取列出的已有记忆编号，无冲突给空数组。\n"
                + "既有记忆（含停用，停用记忆不得被复活）：\n" + existingBlock + "\n候选：\n" + candidateBlock;
    }

    private List<Judgement> parse(List<CandidateForJudgement> candidates, List<ExistingMemories> existing, String text) {
        try {
            String json = jsonObject(text);
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                throw new IllegalStateException("记忆写入判断输出必须是 JSON 数组");
            }
            Set<Integer> slots = new HashSet<>();
            List<Judgement> judgements = new ArrayList<>();
            for (JsonNode item : array) {
                int slot = item.path("candidateIndex").asInt(-1);
                if (slot < 0 || slot >= candidates.size() || !slots.add(slot)) {
                    throw new IllegalStateException("记忆写入判断候选编号非法：" + slot);
                }
                MemoryWriteOutcome outcome = parseOutcome(item.path("verdict").asText(null));
                List<Long> conflicts = new ArrayList<>();
                JsonNode conflictsNode = item.path("conflictsWith");
                if (conflictsNode.isArray()) {
                    for (JsonNode id : conflictsNode) {
                        long value = id.asLong(-1);
                        if (value < 0 || existing.stream().noneMatch(entry -> entry.id() == value)) {
                            throw new IllegalStateException("记忆写入判断 conflictsWith 引用了不存在的记忆编号：" + value);
                        }
                        conflicts.add(value);
                    }
                }
                String summary = item.has("summary") ? item.path("summary").asText(null) : null;
                if (summary != null && summary.isBlank()) {
                    summary = null;
                }
                if (summary != null && summary.codePointCount(0, summary.length()) > 300) {
                    summary = bound(summary, 300);
                }
                judgements.add(new Judgement(slot, outcome, List.copyOf(conflicts), summary));
            }
            if (judgements.size() != candidates.size()) {
                throw new IllegalStateException("记忆写入判断结论数量不完整：" + judgements.size() + "/" + candidates.size());
            }
            return List.copyOf(judgements);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("记忆写入判断输出非合法 JSON", exception);
        }
    }

    private static MemoryWriteOutcome parseOutcome(String verdict) {
        if (verdict == null) {
            throw new IllegalStateException("记忆写入判断缺少 verdict");
        }
        try {
            return MemoryWriteOutcome.valueOf(verdict);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("记忆写入判断 verdict 非法：" + verdict);
        }
    }

    /** @return 从模型文本中截取最外层 JSON 数组（容忍模型在前后附加说明）。 */
    private static String jsonObject(String text) {
        String stripped = text.strip();
        int start = stripped.indexOf('[');
        int end = stripped.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("记忆写入判断输出中未找到 JSON 数组");
        }
        return stripped.substring(start, end + 1);
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        return bound(value, limit);
    }

    static String bound(String value, int limit) {
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }
}
