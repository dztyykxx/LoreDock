package io.github.loredock.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.api.MemoryWriteDecision;
import io.github.loredock.memory.api.MemoryWriteOutcome;
import io.github.loredock.memory.api.MemoryWriteRelation;
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
 * 用户记忆关系判断器。模型只负责语义关系和合并文本，目标 ID、版本、范围与证据
 * 是否来自服务端由 MemoryServiceImpl 再次校验。
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

    public record CandidateForJudgement(int slot, MemoryCategory category, String title, String summary, String content) {
        public CandidateForJudgement(int slot, MemoryCategory category, String title, String content) {
            this(slot, category, title, null, content);
        }
    }

    /** 判断器可见的完整候选，正文不在此处静默截短。 */
    public record ExistingMemories(Long id, MemoryCategory category, MemoryStatus status,
            String title, String summary, String content, long revision) {
        public ExistingMemories(Long id, MemoryCategory category, MemoryStatus status,
                String title, String summary) {
            this(id, category, status, title, summary, "", 1L);
        }
    }

    public record ConflictDetail(Long memoryId, Integer candidateIndex, String oldClause, String newClause) {
    }

    public record Judgement(int slot, MemoryWriteRelation relation, MemoryWriteDecision decision,
            Long targetId, Integer targetCandidateIndex, String title, String summary, String content,
            String reason, List<String> changes, List<ConflictDetail> conflicts,
            String recommendation, String question, String replacementEvidence) {

        /** 兼容旧模型回执，正式新提示词不再生成冲突双 ACTIVE。 */
        public Judgement(int slot, MemoryWriteOutcome outcome, List<Long> conflictsWith, String summary) {
            this(slot, relationOf(outcome), decisionOf(outcome),
                    conflictsWith == null || conflictsWith.isEmpty() ? null : conflictsWith.get(0), null,
                    null, summary, null, "兼容旧版判断回执", List.of(), details(conflictsWith), null, null, null);
        }

        public MemoryWriteOutcome outcome() {
            if (decision == MemoryWriteDecision.CONFIRM) return MemoryWriteOutcome.NEEDS_CONFIRMATION;
            if (decision == MemoryWriteDecision.SKIP) {
                return relation == MemoryWriteRelation.DUPLICATE
                        ? MemoryWriteOutcome.SKIP_DUPLICATE : MemoryWriteOutcome.SKIP_NOT_WORTH;
            }
            if (relation == MemoryWriteRelation.CONFLICT && decision == MemoryWriteDecision.CREATE) {
                return MemoryWriteOutcome.CONFLICT_CREATED;
            }
            return decision == MemoryWriteDecision.UPDATE ? MemoryWriteOutcome.UPDATED : MemoryWriteOutcome.CREATED;
        }

        public List<Long> conflictsWith() {
            return conflicts == null ? List.of() : conflicts.stream().map(ConflictDetail::memoryId)
                    .filter(java.util.Objects::nonNull).toList();
        }

        private static List<ConflictDetail> details(List<Long> ids) {
            if (ids == null) return List.of();
            return ids.stream().map(id -> new ConflictDetail(id, null, "", "")).toList();
        }

        private static MemoryWriteRelation relationOf(MemoryWriteOutcome outcome) {
            return switch (outcome) {
                case SKIP_DUPLICATE -> MemoryWriteRelation.DUPLICATE;
                case CONFLICT_CREATED -> MemoryWriteRelation.CONFLICT;
                default -> MemoryWriteRelation.NEW;
            };
        }

        private static MemoryWriteDecision decisionOf(MemoryWriteOutcome outcome) {
            return switch (outcome) {
                case SKIP_DUPLICATE, SKIP_NOT_WORTH -> MemoryWriteDecision.SKIP;
                default -> MemoryWriteDecision.CREATE;
            };
        }
    }

    /** 单候选判断，批内工作视图由服务端在每次调用前构造。 */
    public List<Judgement> judge(List<CandidateForJudgement> candidates, List<ExistingMemories> existing) {
        String prompt = buildPrompt(candidates, existing);
        log.info("记忆写入判断 agent=memory_write_judger candidates={} 既有记忆={}",
                candidates.size(), existing == null ? 0 : existing.size());
        ChatModel chatModel = model.getIfAvailable();
        if (chatModel == null) throw new IllegalStateException(MODEL_UNAVAILABLE);
        ChatResponse response = chatModel.call(new Prompt(List.of(new UserMessage(prompt))));
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) throw new IllegalStateException("记忆写入判断无有效模型输出");
        List<Judgement> result = parse(candidates, existing == null ? List.of() : existing, text);
        log.info("记忆写入判断完成 结论={}", result.stream().map(item -> item.relation() + ":" + item.slot()).toList());
        return result;
    }

    private String buildPrompt(List<CandidateForJudgement> candidates, List<ExistingMemories> existing) {
        String oldBlock = existing == null || existing.isEmpty() ? "（无既存记忆）" : existing.stream()
                .map(item -> "#" + item.id() + " [" + item.status() + " " + item.category()
                        + "]\n标题：" + bounded(item.title(), 200) + "\n摘要：" + bounded(item.summary(), 300)
                        + "\n正文：" + bounded(item.content(), 4000))
                .reduce((a, b) -> a + "\n---\n" + b).orElse("");
        String newBlock = candidates.stream().map(item -> "候选" + item.slot() + " 分类=" + item.category()
                        + "\n标题：" + bounded(item.title(), 200) + "\n建议摘要：" + bounded(item.summary(), 300)
                        + "\n正文：" + bounded(item.content(), 4000))
                .reduce((a, b) -> a + "\n---\n" + b).orElse("");
        return "你是用户长期偏好记忆合并判断器，只处理用户对文档产出的长期偏好。\n"
                + "DUPLICATE=旧正文已完整覆盖新要求；INCREMENTAL=同一偏好增加兼容条款；"
                + "CONFLICT=同一条件下互斥。一次性指令返回 relation=NEW,decision=SKIP。\n"
                + "明确用户改口且有证据时可 relation=CONFLICT,decision=UPDATE；未明确改口返回 decision=CONFIRM。"
                + "停用目标不得复活。UPDATE 必须返回包含旧有效约束的完整合并正文；SKIP/CONFIRM 不要伪造正文。\n"
                + "只输出 JSON 数组：[ {\"candidateIndex\":0,\"relation\":\"INCREMENTAL\","
                + "\"decision\":\"UPDATE\",\"targetId\":1,\"targetCandidateIndex\":null,"
                + "\"title\":\"...\",\"summary\":\"...\",\"content\":\"完整正文\","
                + "\"reason\":\"...\",\"changes\":[\"...\"],\"conflicts\":[],"
                + "\"recommendation\":null,\"question\":null,\"replacementEvidence\":null} ]\n"
                + "targetId 只能引用下列已有编号；同批新候选目标才使用 targetCandidateIndex，二者互斥。"
                + "conflicts 每项包含 memoryId 或 candidateIndex、oldClause、newClause。\n"
                + "既有记忆：\n" + oldBlock + "\n候选：\n" + newBlock;
    }

    private List<Judgement> parse(List<CandidateForJudgement> candidates,
            List<ExistingMemories> existing, String text) {
        try {
            JsonNode array = objectMapper.readTree(jsonArray(text));
            if (!array.isArray()) throw new IllegalStateException("判断结果必须是 JSON 数组");
            Set<Integer> slots = new HashSet<>();
            List<Judgement> result = new ArrayList<>();
            for (JsonNode item : array) {
                int slot = item.path("candidateIndex").asInt(-1);
                if (slot < 0 || slot >= candidates.size() || !slots.add(slot)) {
                    throw new IllegalStateException("判断候选编号非法：" + slot);
                }
                if (item.has("verdict")) {
                    result.add(parseLegacy(item, slot, existing));
                    continue;
                }
                MemoryWriteRelation relation = enumValue(MemoryWriteRelation.class, item.path("relation").asText(null));
                MemoryWriteDecision decision = enumValue(MemoryWriteDecision.class, item.path("decision").asText(null));
                Long targetId = item.path("targetId").isNumber() ? item.path("targetId").longValue() : null;
                Integer targetCandidate = item.path("targetCandidateIndex").isInt()
                        ? item.path("targetCandidateIndex").intValue() : null;
                if (targetId != null && targetCandidate != null) throw new IllegalStateException("目标引用互斥");
                if (targetId != null && existing.stream().noneMatch(old -> old.id().equals(targetId))) {
                    throw new IllegalStateException("引用了不存在的目标记忆：" + targetId);
                }
                List<ConflictDetail> conflicts = new ArrayList<>();
                JsonNode conflictNode = item.path("conflicts");
                if (conflictNode.isArray()) for (JsonNode c : conflictNode) {
                    Long memoryId = c.path("memoryId").isNumber() ? c.path("memoryId").longValue() : null;
                    Integer candidateIndex = c.path("candidateIndex").isInt() ? c.path("candidateIndex").intValue() : null;
                    if (memoryId == null && candidateIndex == null || memoryId != null && candidateIndex != null) {
                        throw new IllegalStateException("冲突目标引用非法");
                    }
                    conflicts.add(new ConflictDetail(memoryId, candidateIndex,
                            bounded(c.path("oldClause").asText(""), 500),
                            bounded(c.path("newClause").asText(""), 500)));
                }
                result.add(new Judgement(slot, relation, decision, targetId, targetCandidate,
                        nullable(item, "title"), nullable(item, "summary"), nullable(item, "content"),
                        bounded(item.path("reason").asText(""), 300), strings(item.path("changes"), 5, 200),
                        List.copyOf(conflicts), nullable(item, "recommendation"), nullable(item, "question"),
                        nullable(item, "replacementEvidence")));
            }
            if (result.size() != candidates.size()) throw new IllegalStateException("判断结论数量不完整");
            return List.copyOf(result);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("判断输出非合法 JSON", e);
        }
    }

    private Judgement parseLegacy(JsonNode item, int slot, List<ExistingMemories> existing) {
        MemoryWriteOutcome outcome = enumValue(MemoryWriteOutcome.class, item.path("verdict").asText(null));
        List<ConflictDetail> conflicts = new ArrayList<>();
        JsonNode ids = item.path("conflictsWith");
        if (ids.isArray()) for (JsonNode id : ids) {
            long value = id.asLong(-1);
            if (existing.stream().noneMatch(old -> old.id() == value)) throw new IllegalStateException("冲突编号不存在");
            conflicts.add(new ConflictDetail(value, null, "", ""));
        }
        return new Judgement(slot, outcome, conflicts.stream().map(ConflictDetail::memoryId).toList(),
                nullable(item, "summary"));
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少枚举字段");
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("枚举值非法：" + value); }
    }

    private static List<String> strings(JsonNode node, int maxItems, int maxLength) {
        if (!node.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) { if (result.size() == maxItems) break; result.add(bounded(item.asText(""), maxLength)); }
        return List.copyOf(result);
    }

    private static String nullable(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static String jsonArray(String text) {
        String stripped = text.strip(); int start = stripped.indexOf('['); int end = stripped.lastIndexOf(']');
        if (start < 0 || end <= start) throw new IllegalStateException("未找到 JSON 数组");
        return stripped.substring(start, end + 1);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        String text = value.strip(); int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }
}
