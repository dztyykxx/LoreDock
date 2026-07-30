package io.github.loredock.agent.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectQaResultValidatorTest {

    private final ProjectQaResultValidator validator = new ProjectQaResultValidator();
    private final UUID runId = UUID.randomUUID();

    /**
     * 业务目的：业务规则回答必须至少引用本运行保留的人工知识，防止模型用常识伪造内部规则。
     */
    @Test
    void businessRuleAnswerRequiresRetainedKnowledgeEvidence() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "业务规则回答", null,
                List.of(knowledge.id()));

        TrustedProjectQaResult trusted = validator.validate(runId, true, result, List.of(knowledge));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(trusted.citations()).containsExactly(knowledge.id());
        System.out.printf("测试证据：场景=业务规则有效引用，runId=%s，citationCount=%d%n",
                runId, trusted.citations().size());
    }

    /**
     * 业务目的：当前实现回答只能由固定活动快照代码支撑，无快照时必须给出可解释拒答。
     */
    @Test
    void implementationAnswerWithoutSnapshotBecomesRefusal() {
        AgentEvidence code = evidence(EvidenceSourceType.CODE, true, runId);
        ProjectQaModelResult result = new ProjectQaModelResult(
                AgentResultType.ANSWER, AnswerBasis.CURRENT_IMPLEMENTATION, "实现回答", null, List.of(code.id()));

        TrustedProjectQaResult trusted = validator.validate(runId, false, result, List.of(code));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(trusted.refusalReason()).isEqualTo(AgentRefusalReason.CODE_SNAPSHOT_NOT_INDEXED);
        assertThat(trusted.text()).contains("当前知识库没有足够依据");
        System.out.printf("测试证据：场景=无快照实现问题拒答，reason=%s%n", trusted.refusalReason());
    }

    /**
     * 业务目的：空引用、伪造引用、跨运行引用和被裁剪证据都不能形成可信回答。
     */
    @Test
    void invalidCitationVariantsBecomeTrustedRefusal() {
        AgentEvidence otherRun = evidence(EvidenceSourceType.KNOWLEDGE, true, UUID.randomUUID());
        AgentEvidence trimmed = evidence(EvidenceSourceType.KNOWLEDGE, false, runId);
        List<ProjectQaModelResult> invalidResults = List.of(
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null, List.of()),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(UUID.randomUUID())),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(otherRun.id())),
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE, "回答", null,
                        List.of(trimmed.id()))
        );

        List<TrustedProjectQaResult> trusted = invalidResults.stream()
                .map(result -> validator.validate(runId, true, result, List.of(otherRun, trimmed)))
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
     * 业务目的：混合结论必须同时引用知识与代码，来源冲突拒答也必须展示两类当前范围证据。
     */
    @Test
    void mixedAnswerAndSourceConflictRequireBothEvidenceTypes() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        AgentEvidence code = evidence(EvidenceSourceType.CODE, true, runId);
        TrustedProjectQaResult mixed = validator.validate(runId, true,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.MIXED, "混合回答", null,
                        List.of(knowledge.id(), code.id())), List.of(knowledge, code));
        TrustedProjectQaResult conflict = validator.validate(runId, true,
                new ProjectQaModelResult(AgentResultType.REFUSAL, AnswerBasis.MIXED, "来源冲突",
                        AgentRefusalReason.SOURCE_CONFLICT, List.of(knowledge.id(), code.id())),
                List.of(knowledge, code));

        assertThat(mixed.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(conflict.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(conflict.refusalReason()).isEqualTo(AgentRefusalReason.SOURCE_CONFLICT);
        assertThat(conflict.citations()).hasSize(2);
        System.out.printf("测试证据：场景=双来源要求，mixedCitations=%d，conflictCitations=%d%n",
                mixed.citations().size(), conflict.citations().size());
    }

    /**
     * 业务目的：模型声称已经发布或修改正式知识时必须拒绝，确保 Agent 不能绕过人工审核边界。
     */
    @Test
    void forgedPublicationClaimBecomesRefusal() {
        AgentEvidence knowledge = evidence(EvidenceSourceType.KNOWLEDGE, true, runId);
        TrustedProjectQaResult trusted = validator.validate(runId, true,
                new ProjectQaModelResult(AgentResultType.ANSWER, AnswerBasis.BUSINESS_RULE,
                        "我已发布正式知识并修改项目配置", null, List.of(knowledge.id())), List.of(knowledge));

        assertThat(trusted.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(trusted.refusalReason()).isEqualTo(AgentRefusalReason.OUTPUT_POLICY_VIOLATION);
        System.out.printf("测试证据：场景=伪造发布声明拒答，reason=%s%n", trusted.refusalReason());
    }

    private AgentEvidence evidence(EvidenceSourceType type, boolean retained, UUID evidenceRunId) {
        return new AgentEvidence(
                UUID.randomUUID(), evidenceRunId, type, retained, 0.9,
                type == EvidenceSourceType.KNOWLEDGE ? UUID.randomUUID() : null,
                type == EvidenceSourceType.CODE ? UUID.randomUUID() : null,
                "atlas", "main", type == EvidenceSourceType.CODE ? "abcdef1" : null,
                type == EvidenceSourceType.CODE ? "src/App.java" : null,
                type == EvidenceSourceType.KNOWLEDGE ? "业务规则" : null,
                Instant.parse("2026-07-30T12:00:00Z"));
    }
}
