package io.github.loredock.agent.converter;

import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将不可信模型输出转换为可发布回答或模板化拒答。校验失败绝不保留模型回答正文。
 */
public final class ProjectQaResultConverter {

    public static final String REFUSAL_TEXT = "当前知识库没有足够依据";

    /**
     * 校验引用属于当前运行、未被裁剪且满足回答来源类型。
     *
     * @param runId 当前运行标识
     * @param modelResult 不可信模型结果
     * @param evidenceLedger 当前运行证据台账
     * @return 可信回答或带稳定原因的拒答
     */
    public TrustedProjectQaResult validate(
            Long runId,
            ProjectQaModelResult modelResult,
            List<AgentEvidence> evidenceLedger
    ) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(modelResult, "modelResult");
        Map<Long, AgentEvidence> evidenceById = new LinkedHashMap<>();
        for (AgentEvidence evidence : evidenceLedger) {
            evidenceById.put(evidence.id(), evidence);
        }

        if (containsPublicationClaim(modelResult.text())) {
            return refusal(AgentRefusalReason.OUTPUT_POLICY_VIOLATION);
        }
        List<AgentEvidence> cited = resolveCitations(runId, modelResult.citationEvidenceIds(), evidenceById);
        if (cited == null) {
            return refusal(AgentRefusalReason.AGENT_CITATION_INVALID);
        }
        if (modelResult.resultType() == AgentResultType.ANSWER) {
            if (modelResult.basis() == null
                    && cited.isEmpty()
                    && modelResult.citationEvidenceIds().isEmpty()
                    && evidenceLedger.isEmpty()) {
                // 普通对话由模型自主选择不检索；一旦本轮产生过证据，就不能借此分支绕过项目事实引用门禁。
                return new TrustedProjectQaResult(
                        AgentResultType.ANSWER, null, requiredText(modelResult.text()), null, List.of());
            }
            if (modelResult.basis() != AnswerBasis.BUSINESS_RULE
                    || cited.isEmpty() || !containsOnlyKnowledge(cited)) {
                return refusal(AgentRefusalReason.AGENT_CITATION_INVALID);
            }
            return new TrustedProjectQaResult(
                    AgentResultType.ANSWER,
                    modelResult.basis(),
                    requiredText(modelResult.text()),
                    null,
                    modelResult.citationEvidenceIds());
        }

        AgentRefusalReason reason = modelResult.refusalReason() == null
                ? AgentRefusalReason.INSUFFICIENT_EVIDENCE : modelResult.refusalReason();
        if (reason == AgentRefusalReason.SOURCE_CONFLICT
                && (cited.size() < 2 || !containsOnlyKnowledge(cited))) {
            return refusal(AgentRefusalReason.AGENT_CITATION_INVALID);
        }
        String text = modelResult.text() == null || modelResult.text().isBlank()
                ? REFUSAL_TEXT : modelResult.text().strip();
        return new TrustedProjectQaResult(
                AgentResultType.REFUSAL, modelResult.basis(), text, reason, modelResult.citationEvidenceIds());
    }

    private List<AgentEvidence> resolveCitations(
            Long runId,
            List<Long> citationIds,
            Map<Long, AgentEvidence> evidenceById
    ) {
        java.util.ArrayList<AgentEvidence> resolved = new java.util.ArrayList<>();
        for (Long citationId : citationIds) {
            AgentEvidence evidence = evidenceById.get(citationId);
            if (evidence == null || !evidence.retained() || !runId.equals(evidence.runId())) {
                return null;
            }
            resolved.add(evidence);
        }
        return List.copyOf(resolved);
    }

    private boolean containsOnlyKnowledge(List<AgentEvidence> cited) {
        return cited.stream().allMatch(value -> value.sourceType() == EvidenceSourceType.KNOWLEDGE);
    }

    private boolean containsPublicationClaim(String text) {
        if (text == null) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("已发布正式知识")
                || compact.contains("已修改正式知识")
                || compact.contains("已批准发布")
                || compact.contains("已修改项目配置");
    }

    private String requiredText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("answer text required");
        }
        return text.strip();
    }

    private TrustedProjectQaResult refusal(AgentRefusalReason reason) {
        return new TrustedProjectQaResult(
                AgentResultType.REFUSAL, null, REFUSAL_TEXT, reason, List.of());
    }
}
