package io.github.loredock.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import io.github.loredock.agent.mapper.KnowledgeTaskMessageMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 受限 LLM 压缩兜底（设计文档 §8）：不是 Agent 运行，由 {@code ContextAssemblyService}
 * 在节点入口、确定性压缩后仍超限时发起一次受控 ChatModel 调用。
 *
 * <p>安全约束：只处理已完成轮次的对话解释与过程说明；保留引用/决定/问题 ID 必须是输入子集，
 * 校验失败即拒绝；不得处理来源事实、人工决定、草稿 revision 或当前节点输入。</p>
 */
public class ContextCompressionService {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionService.class);
    private static final String ROLE_USER = "USER";
    private static final String ROLE_FINAL = "COORDINATOR_AGENT";

    private final ObjectMapper objectMapper;
    private final KnowledgeTaskMessageMapper messages;
    private final ContextTokenEstimator estimator;

    public ContextCompressionService(
            ObjectMapper objectMapper, KnowledgeTaskMessageMapper messages, ContextTokenEstimator estimator) {
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.estimator = estimator;
    }

    /** 压缩输入的一个旧轮次（真实用户消息 + 对应最终回复）。 */
    public record OldTurn(String userText, String assistantText) {
    }

    /** 旧轮读取结果：完整轮次配对的批次与这批读取所覆盖的最大消息 ID（摘要水位用）。 */
    public record OldTurnBatch(List<OldTurn> turns, long throughMessageId) {
    }

    /** LLM 压缩产物：摘要文本与输入中已有 ID 的子集校验结果。 */
    public record CompressionResult(
            String summary,
            List<String> retainedReferenceIds,
            List<String> retainedDecisionIds,
            List<String> retainedQuestionIds
    ) {
    }

    /** 本次压缩的原始输入引用/决定/问题 ID 全集（供子集校验）。 */
    public record IdUniverse(List<String> referenceIds, List<String> decisionIds, List<String> questionIds) {
    }

    /**
     * 读取某消息 ID 之后的完整旧轮次（USER + 最终回复配对；最终回复按会话目标 subject 过滤，
     * 排除同为 {@code COORDINATOR_AGENT} 的公开行动摘要），只用于压缩输入，不进入业务 Agent。
     *
     * @param conversationId 会话标识
     * @param targetSkill 当前会话最终回复的 subject（agentName/targetSkill）
     * @param afterMessageId 起始消息 ID（不含）；通常为上次摘要水位，0 表示从最早消息开始
     * @param maxTurns 最多读取的轮次数（有界批次，不截断半轮）
     */
    public OldTurnBatch readOldTurns(Long conversationId, String targetSkill, long afterMessageId, int maxTurns) {
        List<KnowledgeTaskMessageEntity> entries = messages.selectList(
                        Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                                .eq(KnowledgeTaskMessageEntity::getConversationId, conversationId)
                                .gt(KnowledgeTaskMessageEntity::getId, afterMessageId)
                                .orderByAsc(KnowledgeTaskMessageEntity::getCreatedAt)
                                .orderByAsc(KnowledgeTaskMessageEntity::getId))
                .stream()
                .filter(item -> ROLE_USER.equals(item.getRole())
                        || (ROLE_FINAL.equals(item.getRole()) && targetSkill.equals(item.getSubjectName())))
                .toList();
        List<OldTurn> turns = new ArrayList<>();
        String pendingUser = null;
        long through = afterMessageId;
        for (KnowledgeTaskMessageEntity item : entries) {
            String content = item.getContent() == null ? "" : item.getContent().strip();
            if (content.isBlank()) {
                continue;
            }
            through = item.getId();
            if (ROLE_USER.equals(item.getRole())) {
                pendingUser = content;
            } else if (pendingUser != null) {
                turns.add(new OldTurn(pendingUser, content));
                pendingUser = null;
                if (turns.size() >= maxTurns) {
                    break;
                }
            }
        }
        return new OldTurnBatch(turns, through);
    }

    /**
     * 摘要来源摘要：对截至某条消息 ID 的全部已过滤对话行计算 SHA-256（从最早消息开始），
     * 新消息作为未摘要增量不影响旧摘要；范围消息异常修改时 digest 不匹配从而触发重建。
     *
     * @param conversationId 会话标识
     * @param throughMessageId 摘要覆盖到该消息 ID（含）
     * @param targetSkill 最终回复 subject 过滤
     */
    public String digest(Long conversationId, String targetSkill, long throughMessageId) {
        List<KnowledgeTaskMessageEntity> range = messages.selectList(
                Wrappers.<KnowledgeTaskMessageEntity>lambdaQuery()
                        .eq(KnowledgeTaskMessageEntity::getConversationId, conversationId)
                        .le(KnowledgeTaskMessageEntity::getId, throughMessageId)
                        .orderByAsc(KnowledgeTaskMessageEntity::getId));
        String source = range.stream()
                .filter(item -> ROLE_USER.equals(item.getRole())
                        || (ROLE_FINAL.equals(item.getRole()) && targetSkill.equals(item.getSubjectName())))
                .map(item -> item.getId() + "|" + item.getRole() + "|" + item.getContent())
                .collect(Collectors.joining("\n"));
        return sha256(source);
    }

    /**
     * 发起受限压缩调用；输出必须是输入 ID 子集，否则抛出 {@link IllegalStateException} 由调用方拒绝结果。
     *
     * @param model 本轮共享模型（与业务 Agent 同一模型，无 Tool、无 Saver）
     * @param turns 旧轮次块（有界批次，不截断半轮）
     * @param targetTokens 压缩输出目标 token（预算约束）
     * @param ids 输入中已有的引用/决定/问题 ID 全集
     */
    public CompressionResult summarize(ChatModel model, List<OldTurn> turns, int targetTokens, IdUniverse ids) {
        String blocks = turns.stream()
                .map(turn -> "> 用户：" + bounded(turn.userText(), 600)
                        + "\n> 最终回复：" + bounded(turn.assistantText(), 800))
                .collect(Collectors.joining("\n"));
        String prompt = "你是知识整理会话的上下文压缩器，只处理历史对话解释，不做事实判断。\n"
                + "只输出一个 JSON 对象，不得输出解释：{\"summary\":\"...\",\"retainedReferenceIds\":[],"
                + "\"retainedDecisionIds\":[],\"retainedQuestionIds\":[]}\n"
                + "规则：\n"
                + "- 摘要只保留目标、已确认决定、未决问题和稳定引用指代，不保存知识正文、草稿正文或执行事实；\n"
                + "- retainedReferenceIds / retainedDecisionIds / retainedQuestionIds 只能选择输入方提供的 ID；\n"
                + "- 目标预算 " + targetTokens + " token，摘要不超过 1200 字。\n"
                + "历史对话：\n" + blocks;
        List<org.springframework.ai.chat.messages.Message> request = List.of(new UserMessage(prompt));
        log.info("上下文压缩调用 agent=context_compressor 输入轮次={} 估算token={} targetTokens={}",
                turns.size(), estimator.estimate(request).tokens(), targetTokens);
        ChatResponse response = model.call(new Prompt(request));
        org.springframework.ai.chat.metadata.Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (response != null && (usage == null || usage.getPromptTokens() == null || usage.getCompletionTokens() == null)) {
            log.info("agent_model_completed runId=- agent=context_compressor callSeq=1 estimatedInputTokens={} "
                            + "actualInputTokens=null actualOutputTokens=null（模型未返回 usage）",
                    estimator.estimate(request).tokens());
        } else if (usage != null) {
            log.info("agent_model_completed runId=- agent=context_compressor callSeq=1 estimatedInputTokens={} "
                            + "actualInputTokens={} actualOutputTokens={}",
                    estimator.estimate(request).tokens(), usage.getPromptTokens(), usage.getCompletionTokens());
        }
        String text = response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? null : response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("上下文压缩调用无有效输出");
        }
        CompressionResult result = parse(text);
        validateSubset(result, ids);
        log.info("上下文压缩调用 agent=context_compressor 完成 摘要字数={} 保留引用={} 保留决定={}",
                result.summary().length(), result.retainedReferenceIds().size(), result.retainedDecisionIds().size());
        return result;
    }

    private CompressionResult parse(String text) {
        try {
            String json = jsonObject(text);
            JsonNode node = objectMapper.readTree(json);
            JsonNode summaryNode = node.get("summary");
            if (summaryNode == null || summaryNode.asText().isBlank()) {
                throw new IllegalStateException("压缩结果缺少 summary");
            }
            return new CompressionResult(summaryNode.asText(),
                    ids(node.get("retainedReferenceIds")), ids(node.get("retainedDecisionIds")),
                    ids(node.get("retainedQuestionIds")));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("压缩结果非合法 JSON：" + bounded(String.valueOf(exception.getMessage()), 240), exception);
        }
    }

    private static List<String> ids(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    private static void validateSubset(CompressionResult result, IdUniverse ids) {
        Set<String> references = new HashSet<>(ids.referenceIds());
        Set<String> decisions = new HashSet<>(ids.decisionIds());
        Set<String> questions = new HashSet<>(ids.questionIds());
        validateIds(result.retainedReferenceIds(), references, "reference");
        validateIds(result.retainedDecisionIds(), decisions, "decision");
        validateIds(result.retainedQuestionIds(), questions, "question");
    }

    private static void validateIds(List<String> returned, Set<String> allowed, String kind) {
        for (String id : returned) {
            if (!allowed.contains(id)) {
                throw new IllegalStateException("压缩结果包含输入中不存在的 " + kind + " ID：" + id);
            }
        }
    }

    /** @return 从模型文本中截取最外层 JSON（容忍模型在 JSON 前后附加说明）。 */
    private static String jsonObject(String text) {
        String stripped = text.strip();
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("压缩输出中未找到 JSON 对象");
        }
        return stripped.substring(start, end + 1);
    }

    private static String bounded(String value, int limit) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private static String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("摘要计算失败", exception);
        }
    }
}
