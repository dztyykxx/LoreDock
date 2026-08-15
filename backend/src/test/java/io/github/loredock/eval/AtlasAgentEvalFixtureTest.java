package io.github.loredock.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.eval.AtlasAgentEvalFixture.CurationCase;
import io.github.loredock.eval.AtlasAgentEvalFixture.DocumentSpec;
import io.github.loredock.eval.AtlasAgentEvalFixture.EvalData;
import io.github.loredock.eval.AtlasAgentEvalFixture.Manifest;
import io.github.loredock.eval.AtlasAgentEvalFixture.QaCase;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 评估数据集加载与最小质量检查的单元测试，保护《Agent 评估测试数据构造要求》第 10 节的检查清单：
 * 用例数量与类型分布、文档 ID 反查、引用文档存在、草稿一一对应、参考回答完整。
 */
class AtlasAgentEvalFixtureTest {

    /**
     * 业务目的：评估框架必须能从仓库数据目录加载全部数据集并满足 manifest 数量声明，
     * 防止数据整理后框架读到缺项或过期文件而不自知。
     */
    @Test
    void loadsCompleteDatasetFromRepositoryDataDirectory() {
        EvalData data = AtlasAgentEvalFixture.load();

        assertThat(data.manifest().datasetVersion()).isEqualTo("atlas-agent-eval-v1");
        assertThat(data.manifest().projectIdentifier()).isEqualTo("atlas");
        assertThat(data.manifest().qaCaseCount()).isEqualTo(40);
        assertThat(data.manifest().curationCaseCount()).isEqualTo(8);
        assertThat(data.qaCases()).hasSize(40);
        assertThat(data.curationCases()).hasSize(8);
        assertThat(data.documents().stream().filter(document -> document.documentId() != null)).hasSize(22);
        System.out.printf("测试证据：场景=评估数据加载，QA=%d，知识整理=%d，映射文档=%d，数据集=%s%n",
                data.qaCases().size(), data.curationCases().size(),
                data.documents().stream().filter(document -> document.documentId() != null).count(),
                data.manifest().datasetVersion());
    }

    /**
     * 业务目的：QA 用例类型分布必须与构造要求一致（14/10/6/4/3/3），分布变化说明
     * 数据集被调整过，评估口径需要重新确认。
     */
    @Test
    void qaCaseTypeDistributionMatchesSpecification() {
        EvalData data = AtlasAgentEvalFixture.load();
        Map<String, Long> distribution = data.qaCases().stream()
                .collect(java.util.stream.Collectors.groupingBy(QaCase::caseType,
                        java.util.stream.Collectors.counting()));

        assertThat(distribution).containsEntry("SINGLE_DOCUMENT", 14L)
                .containsEntry("MULTI_DOCUMENT", 10L)
                .containsEntry("PARAPHRASE", 6L)
                .containsEntry("INSUFFICIENT_EVIDENCE", 4L)
                .containsEntry("SOURCE_CONFLICT", 3L)
                .containsEntry("SCOPE_OR_LIFECYCLE", 3L);
        System.out.printf("测试证据：场景=QA类型分布，分布=%s%n", distribution);
    }

    /**
     * 业务目的：所有用例引用的文档 ID 必须能通过 manifest 反查业务键并加载正文，
     * 防止评分时引用了不存在于语料的来源。
     */
    @Test
    void everyReferencedDocumentIdResolvesToLoadedDocument() {
        EvalData data = AtlasAgentEvalFixture.load();
        Map<Long, DocumentSpec> byId = new java.util.HashMap<>();
        for (DocumentSpec document : data.documents()) {
            if (document.documentId() != null) {
                byId.put(document.documentId(), document);
            }
        }

        for (QaCase qaCase : data.qaCases()) {
            for (Long documentId : qaCase.expected().documentIds()) {
                assertThat(byId).as(qaCase.caseId() + " 引用文档 " + documentId).containsKey(documentId);
            }
        }
        for (CurationCase curationCase : data.curationCases()) {
            assertThat(byId).as(curationCase.caseId() + " 勾选草稿")
                    .containsKey(curationCase.input().selectedDraftId());
            for (Long documentId : curationCase.expected().relatedDocumentIds()) {
                assertThat(byId).as(curationCase.caseId() + " 关联文档 " + documentId).containsKey(documentId);
            }
        }
        System.out.printf("测试证据：场景=文档ID反查，引用检查=%d 条 QA + %d 条知识整理全部可反查%n",
                data.qaCases().size(), data.curationCases().size());
    }

    /**
     * 业务目的：8 条知识整理用例必须与 8 篇候选草稿一一对应且不复用，
     * 防止同一草稿的多个变体干扰问题识别统计。
     */
    @Test
    void curationCasesMapOneToOneToDraftDocuments() {
        EvalData data = AtlasAgentEvalFixture.load();
        List<Long> draftIds = data.curationCases().stream()
                .map(curationCase -> curationCase.input().selectedDraftId()).toList();

        assertThat(draftIds).containsExactly(720001L, 720002L, 720003L, 720004L, 720005L, 720006L, 720007L, 720008L);
        assertThat(draftIds).doesNotHaveDuplicates();
        for (CurationCase curationCase : data.curationCases()) {
            DocumentSpec draft = data.documentOf(curationCase.input().selectedDraftId());
            assertThat(draft.status()).isEqualTo("DRAFT");
            assertThat(curationCase.expected().finalResponse()).isNotBlank();
        }
        System.out.printf("测试证据：场景=草稿一一对应，草稿=%s 全部为 DRAFT 且有参考回答%n", draftIds);
    }

    /**
     * 业务目的：可回答用例必须携带来源与参考回答，拒答用例不得携带来源，
     * 防止评分口径把拒答当回答或反之。
     */
    @Test
    void qaExpectedFieldsFollowAnswerAndRefusalContract() {
        EvalData data = AtlasAgentEvalFixture.load();
        for (QaCase qaCase : data.qaCases()) {
            if ("ANSWER".equals(qaCase.expected().resultType())) {
                assertThat(qaCase.expected().documentIds()).as(qaCase.caseId() + " 可回答用例来源")
                        .isNotEmpty();
                assertThat(qaCase.expected().resultText()).as(qaCase.caseId() + " 参考回答").isNotBlank();
            } else {
                assertThat(qaCase.expected().resultType()).isEqualTo("REFUSAL");
                assertThat(qaCase.expected().refusalReason()).as(qaCase.caseId() + " 拒答原因").isNotBlank();
                if ("INSUFFICIENT_EVIDENCE".equals(qaCase.expected().refusalReason())) {
                    // 证据不足拒答不允许携带来源；来源冲突拒答必须携带冲突文档，构造要求口径不同。
                    assertThat(qaCase.expected().documentIds()).as(qaCase.caseId() + " 证据不足拒答来源").isEmpty();
                }
            }
        }
        System.out.printf("测试证据：场景=QA预期契约，可回答=%d，拒答=%d%n",
                data.qaCases().stream().filter(c -> "ANSWER".equals(c.expected().resultType())).count(),
                data.qaCases().stream().filter(c -> "REFUSAL".equals(c.expected().resultType())).count());
    }

    /**
     * 业务目的：人工复核前 reviewedByHuman 必须为 false，防止未复核数据被当作终版基线。
     */
    @Test
    void datasetIsNotMarkedAsHumanReviewed() {
        EvalData data = AtlasAgentEvalFixture.load();
        assertThat(data.manifest().reviewedByHuman()).isFalse();
        System.out.println("测试证据：场景=人工复核标记，reviewedByHuman=false 符合构造要求");
    }

    /**
     * 业务目的：质量校验必须拦截引用不存在文档的用例，防止评估运行携带脏数据。
     */
    @Test
    void validationRejectsCaseReferencingUnknownDocument() {
        EvalData data = AtlasAgentEvalFixture.load();
        QaCase broken = new QaCase("QA-X", "SINGLE_DOCUMENT",
                data.qaCases().getFirst().input(),
                new AtlasAgentEvalFixture.QaExpected("ANSWER", null, "参考回答", List.of(999999L)));
        EvalData brokenData = new EvalData(data.manifest(),
                java.util.stream.Stream.concat(data.qaCases().stream().limit(39), java.util.stream.Stream.of(broken))
                        .toList(),
                data.curationCases(), data.documents());

        assertThatThrownBy(() -> AtlasAgentEvalFixture.validate(brokenData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QA-X");
        System.out.println("测试证据：场景=质量校验拦截，引用未知文档的用例被拒绝");
    }

    /**
     * 业务目的：数据目录可通过系统属性显式指定，便于在非仓库工作目录运行评估。
     */
    @Test
    void loadsFromExplicitDataDirectoryProperty() {
        Path dataDir = AtlasAgentEvalFixture.locateDataDir();
        System.setProperty(AtlasAgentEvalFixture.DATA_DIR_PROPERTY, dataDir.toString());
        try {
            EvalData data = AtlasAgentEvalFixture.load();
            assertThat(data.qaCases()).hasSize(40);
            System.out.printf("测试证据：场景=显式数据目录，路径=%s，QA=%d%n", dataDir, data.qaCases().size());
        } finally {
            System.clearProperty(AtlasAgentEvalFixture.DATA_DIR_PROPERTY);
        }
    }

    /**
     * 业务目的：manifest 声明的数量与实际用例数不一致时必须在加载阶段失败，
     * 防止框架用不完整用例集跑出不可比对的指标。
     */
    @Test
    void validationRejectsCountMismatchWithManifest() {
        EvalData data = AtlasAgentEvalFixture.load();
        Manifest stale = new Manifest(data.manifest().datasetVersion(), data.manifest().projectIdentifier(),
                39, data.manifest().curationCaseCount(), data.manifest().reviewedByHuman(),
                data.manifest().documentIdMappings());
        EvalData staleData = new EvalData(stale, data.qaCases(), data.curationCases(), data.documents());

        assertThatThrownBy(() -> AtlasAgentEvalFixture.validate(staleData))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("qaCaseCount");
        System.out.println("测试证据：场景=数量声明校验，manifest 数量与用例数不一致被拒绝");
    }
}
