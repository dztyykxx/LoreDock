package io.github.loredock.agent.converter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.agent.model.enums.AgentRefusalReason;
import io.github.loredock.agent.model.enums.AgentResultType;
import io.github.loredock.agent.model.enums.AnswerBasis;
import io.github.loredock.agent.model.enums.EvidenceSourceType;
import io.github.loredock.agent.model.result.AgentEvidence;
import io.github.loredock.agent.model.result.ProjectQaModelResult;
import io.github.loredock.agent.model.result.TrustedProjectQaResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectQaResultConverterTest {

    private final ProjectQaResultConverter validator = new ProjectQaResultConverter();
    private final Long runId = 8000000000000000194L;

    /**
     * 业务目的：业务规则回答必须至少引用本运行保留的人工知识，防止模型用常识伪造内部规则。
     */
    @Test
    void businessRuleAnswerRequiresRetainedKnowledgeEvidence() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "业务规则回答", null,
                List.of(knowledge.id()));

        TrustedProjectQaResult trusted = validator.validate(runId, result, List.of(knowledge));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(trusted.citations()).containsExactly(knowledge.id());
        System.out.printf("测试证据：场景=业务规则有效引用，runId=%s，citationCount=%d%n",
                runId, trusted.citations().size());
    }

    /**
     * 业务目的：闲聊和会话历史类问题不需要项目知识证据，模型明确选择直接回答时不能被引用校验误砍。
     */
    @Test
    void generalConversationAnswerDoesNotRequireKnowledgeEvidence() {
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, null, "本轮之前你提问过两次。", null, List.of());

        TrustedProjectQaResult trusted = validator.validate(runId, result, List.of());

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(trusted.basis()).isNull();
        assertThat(trusted.citations()).isEmpty();
        assertThat(trusted.text()).isEqualTo("本轮之前你提问过两次。");
        System.out.printf("测试证据：场景=无需RAG的普通对话，runId=%s，citationCount=%d，结果=%s%n",
                runId, trusted.citations().size(), trusted.resultType());
    }

    /**
     * 业务目的：已经执行过知识检索的回答不能伪装成普通对话绕过引用门禁，防止项目事实失去来源。
     */
    @Test
    void retrievedProjectAnswerCannotBypassCitationValidationAsConversation() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, null, "没有引用的项目回答", null, List.of());

        TrustedProjectQaResult trusted = validator.validate(runId, result, List.of(knowledge));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(trusted.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        System.out.printf("测试证据：场景=检索后禁止绕过引用，runId=%s，evidenceCount=1，reason=%s%n",
                runId, trusted.refusalReason());
    }

    /**
     * 业务目的：即使底层仍存在历史代码证据，问答也不得形成当前实现回答。
     */
    @Test
    void legacyImplementationAnswerBecomesRefusal() {
        AgentEvidence code = evidence(EvidenceSourceType.CODE, true, runId);
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.CURRENT_IMPLEMENTATION, "实现回答", null, List.of(code.id()));

        TrustedProjectQaResult trusted = validator.validate(runId, result, List.of(code));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(trusted.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        assertThat(trusted.text()).contains("当前知识库没有足够依据");
        System.out.printf("测试证据：场景=代码依据类型已停用，reason=%s%n", trusted.refusalReason());
    }

    /**
     * 业务目的：空引用、伪造引用、跨运行引用和被裁剪证据都不能形成可信回答。
     */
    @Test
    void invalidCitationVariantsBecomeTrustedRefusal() {
        AgentEvidence otherRun = evidence(EvidenceSourceType.KNOWLEDGE, true, 8000000000000000195L);
        AgentEvidence trimmed = evidence(EvidenceSourceType.KNOWLEDGE, false, runId);
        List<ProjectQaModelResult> invalidResults = List.of(
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null, List.of()),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(8000000000000000196L)),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(otherRun.id())),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(trimmed.id()))
        );

        List<TrustedProjectQaResult> trusted = invalidResults.stream()
                .map(result -> validator.validate(runId, result, List.of(otherRun, trimmed)))
                .toList();

        assertThat(trusted).allSatisfy(value -> {
            assertThat(value.resultType()).isEqualTo(AgentResultType.REFUSAL);
            assertThat(value.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
            assertThat(value.citations()).isEmpty();
        });
        System.out.printf("测试证据：场景=四类非法引用拒答，variantCount=%d，reason=%s%n",
                trusted.size(), trusted.getFirst().refusalReason());
    }

    /**
     * 业务目的：文档冲突拒答必须引用至少两条知识证据，混合回答不能再通过服务端校验。
     */
    @Test
    void mixedAnswerIsRejectedAndDocumentConflictRequiresTwoKnowledgeSources() {
        AgentEvidence first = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        AgentEvidence second = new AgentEvidence(
                8000000000000000300L, runId, EvidenceSourceType.KNOWLEDGE, true, 0.8,
                8000000000000000301L, null, "atlas", "main", null, null,
                "另一份业务规则", Instant.parse("2026-07-30T12:00:00Z"));
        AgentEvidence code = evidence(EvidenceSourceType.CODE, true, runId);
        TrustedProjectQaResult mixed = validator.validate(runId,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.MIXED, "混合回答", null,
                        List.of(first.id(), code.id())), List.of(first, code));
        TrustedProjectQaResult conflict = validator.validate(runId,
                new ProjectQaModelResult(AgentResultType.REFUSAL, AnswerBasis.BUSINESS_RULE, "来源冲突",
                        AgentRefusalReason.SOURCE_CONFLICT, List.of(first.id(), second.id())),
                List.of(first, second));

        assertThat(mixed.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(mixed.refusalReason()).isEqualTo(AgentRefusalReason.AGENT_CITATION_INVALID);
        assertThat(conflict.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(conflict.refusalReason()).isEqualTo(AgentRefusalReason.SOURCE_CONFLICT);
        assertThat(conflict.citations()).hasSize(2);
        System.out.printf("测试证据：场景=仅文档来源要求，mixedCitations=%d，conflictCitations=%d%n",
                mixed.citations().size(), conflict.citations().size());
    }

    /**
     * 业务目的：模型声称已经发布或修改正式知识时必须拒绝，确保 Agent 不能绕过人工审核边界。
     */
    @Test
    void forgedPublicationClaimBecomesRefusal() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        TrustedProjectQaResult trusted = validator.validate(runId,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        "我已发布正式知识并修改项目配置", null, List.of(knowledge.id())), List.of(knowledge));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(trusted.refusalReason()).isEqualTo(AgentRefusalReason.OUTPUT_POLICY_VIOLATION);
        System.out.printf("测试证据：场景=伪造发布声明拒答，reason=%s%n", trusted.refusalReason());
    }

    private AgentEvidence evidence(EvidenceSourceType type, boolean retained, Long evidenceRunId) {
        long evidenceId = type == EvidenceSourceType.KNOWLEDGE
                ? 8000000000000000197L : 8000000000000000200L;
        if (!retained) {
            evidenceId += 10;
        }
        if (!runId.equals(evidenceRunId)) {
            evidenceId += 20;
        }
        return new AgentEvidence(
                evidenceId, evidenceRunId, type, retained, 0.9,
                type == EvidenceSourceType.KNOWLEDGE ? 8000000000000000198L : null,
                type == EvidenceSourceType.CODE ? 8000000000000000199L : null,
                "atlas", "main", type == EvidenceSourceType.CODE ? "abcdef1" : null,
                type == EvidenceSourceType.CODE ? "src/App.java" : null,
                type == EvidenceSourceType.KNOWLEDGE ? "业务规则" : null,
                Instant.parse("2026-07-30T12:00:00Z"));
    }
}
