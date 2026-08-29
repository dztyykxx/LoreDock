package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证知识整理 Graph 条件边的安全失败与返工上限（设计文档第 9 节）。 */
class KnowledgeCurationGraphRoutingTest {

    private final KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper());

    private OverAllState state(Map<String, Object> data) {
        return OverAllStateBuilder.builder().withData(data).build();
    }

    /**
     * 业务目的：DECIDE 阶段输出 DRAFT 却没有 draftInstruction、或检索结果没有 SUPPORTED 事实时必须安全失败，
     * 防止调度 Agent 让草稿 Agent 写入无来源或越界的事实。
     */
    @Test
    void decideDraftRequiresInstructionAndSupportedFact() {
        assertThatThrownBy(() -> factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"s\"}",
                "retrievalResult", "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":1,"
                        + "\"facts\":[{\"statement\":\"a\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"r\"}"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺少写入要求或已支持事实");

        String route = factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"r\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"s\"}",
                "retrievalResult", "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":1,"
                        + "\"facts\":[{\"statement\":\"a\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"r\"}")));
        assertThat(route).isEqualTo("DRAFT");
        System.out.println("测试证据：场景=DECIDE DRAFT 校验，无要求/无支持事实=安全失败，齐全=进入草稿");
    }

    /**
     * 业务目的：DECIDE 阶段输出 ASK_USER 却没有具体问题、或 START 阶段 CHAT 没有可见回复时必须安全失败，
     * 防止结束本轮却没有给管理员可回应的实质内容。
     */
    @Test
    void askUserAndChatRequireVisibleText() {
        assertThatThrownBy(() -> factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"ASK_USER\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"s\"}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有具体问题");

        assertThatThrownBy(() -> factory.coordinatorRoute(state(Map.of(
                "stage", "START",
                "coordinationResult", "{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":null}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有可见回复");
        System.out.println("测试证据：场景=ASK_USER/CHAT 空文本校验，缺少问题或回复=安全失败");
    }

    /**
     * 业务目的：草稿 Agent 输出 WRITTEN 却没有 draftId+revision、或审查 Agent 输出 PASS 却没有 reviewedDrafts
     * 时必须安全失败，防止把并未真实写入或未经核对的内容当成可发布结果。
     */
    @Test
    void writtenAndPassRequireDraftEntries() {
        assertThatThrownBy(() -> factory.draftRoute(state(Map.of(
                "draftResult", "{\"status\":\"WRITTEN\",\"drafts\":[],\"question\":null,\"summary\":\"s\"}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有 draftId + revision");

        assertThatThrownBy(() -> factory.reviewRoute(state(Map.of(
                "reviewResult", "{\"verdict\":\"PASS\",\"reviewedDrafts\":[],\"findings\":[],"
                        + "\"question\":null,\"summary\":\"s\"}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有 reviewedDrafts");
        System.out.println("测试证据：场景=WRITTEN/PASS 无修订校验，无 draftId/reviewedDrafts=安全失败");
    }

    /**
     * 业务目的：审查持续 REVISE 时最多返工两轮（draftRound 最大 2），达到上限后不再进入草稿节点，
     * 最后交给人工且不发布；防止无限返工消耗资源。
     */
    @Test
    void reworkStopsAtTwoRoundsAndOftenRejectsEmptyFindings() {
        assertThatThrownBy(() -> factory.reviewRoute(state(Map.of(
                "draftRound", 0,
                "reviewResult", "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"s\"}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有可执行 finding");

        String finding = "{\"code\":\"UNSUPPORTED_CLAIM\",\"draftId\":19,\"description\":\"d\",\"suggestion\":\"s\"}";
        assertThat(factory.reviewRoute(state(Map.of(
                "draftRound", 0,
                "reviewResult", "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[" + finding + "],\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("REVISE");
        assertThat(factory.reviewRoute(state(Map.of(
                "draftRound", 2,
                "reviewResult", "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[" + finding + "],\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("REVISE_LIMIT");
        System.out.println("测试证据：场景=返工上限，draftRound=0->REVISE，draftRound=2->REVISE_LIMIT，空finding=安全失败");
    }
}
