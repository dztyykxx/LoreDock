package io.github.loredock.agent.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.OverAllStateBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 验证知识整理 Graph 条件边的安全路由：校验失败进入修复回路（fix_{agent}）而非逃逸异常，以及返工上限（设计 §7/§9/§11.2）。 */
class KnowledgeCurationGraphRoutingTest {

    /** runId=68 生产实测的违规 memo：模型把完整回复写进 memo，长度超过 100 码点（防御截断后恰好触顶）。 */
    private static final String runId68LongMemo =
            "你好！当前知识整理会话仍处于待你确认阶段：合并基线 draftRef 19 revision 1 已写入工作区但未正式发布，"
                    + "A–H 待人工判断项均在等待你逐项表态。你可以直接告诉我你的决定（或只对拿不定主意的项表态）。";

    private final KnowledgeCurationGraphFactory factory = new KnowledgeCurationGraphFactory(new ObjectMapper(), ContextAssemblyFixtures.assembly(new ObjectMapper()));

    private OverAllState state(Map<String, Object> data) {
        return OverAllStateBuilder.builder().withData(data).build();
    }

    /**
     * 业务目的：DECIDE 阶段输出 DRAFT 却没有 draftInstruction、或检索结果没有 SUPPORTED 事实时必须进入修复回路（fix_coordinator），
     * 防止调度 Agent 让草稿 Agent 写入无来源或越界的事实。
     */
    @Test
    void decideDraftRequiresInstructionAndSupportedFact() {
        assertThat(factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"s\"}",
                "retrievalResult", "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":1,"
                        + "\"facts\":[{\"statement\":\"a\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"r\"}"))))
                .isEqualTo("fix_coordinator");

        String route = factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"r\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"s\"}",
                "retrievalResult", "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":1,"
                        + "\"facts\":[{\"statement\":\"a\",\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],"
                        + "\"unresolvedQuestions\":[],\"summary\":\"r\"}")));
        assertThat(route).isEqualTo("DRAFT");
        System.out.println("测试证据：场景=DECIDE DRAFT 校验，无要求/无支持事实=进入修复回路，齐全=进入草稿");
    }

    /**
     * 业务目的：DECIDE 阶段输出 ASK_USER 却没有具体问题、或 START 阶段 CHAT 没有可见回复时必须进入修复回路（fix_coordinator），
     * 防止结束本轮却没有给管理员可回应的实质内容。
     */
    @Test
    void askUserAndChatRequireVisibleText() {
        assertThat(factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"ASK_USER\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("fix_coordinator");

        assertThat(factory.coordinatorRoute(state(Map.of(
                "stage", "START",
                "coordinationResult", "{\"stage\":\"START\",\"action\":\"CHAT\",\"reason\":\"r\","
                        + "\"draftInstruction\":null,\"question\":null,\"summary\":null}"))))
                .isEqualTo("fix_coordinator");
        System.out.println("测试证据：场景=ASK_USER/CHAT 空文本校验，缺少问题或回复=进入修复回路");
    }

    /**
     * 业务目的：草稿 Agent 输出 WRITTEN 却没有 draftId+revision、或审查 Agent 输出 PASS 却没有 reviewedDrafts
     * 时必须进入修复回路（fix_coordinator），防止把并未真实写入或未经核对的内容当成可发布结果。
     */
    @Test
    void writtenAndPassRequireDraftEntries() {
        assertThat(factory.draftRoute(state(Map.of(
                "draftResult", "{\"status\":\"WRITTEN\",\"drafts\":[],\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("fix_drafter");

        assertThat(factory.reviewRoute(state(Map.of(
                "reviewResult", "{\"verdict\":\"PASS\",\"reviewedDrafts\":[],\"findings\":[],"
                        + "\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("fix_reviewer");
        System.out.println("测试证据：场景=WRITTEN/PASS 无修订校验，无 draftId/reviewedDrafts=进入修复回路");
    }

    /**
     * 业务目的：审查持续 REVISE 时最多返工两轮（draftRound 最大 2），达到上限后不再进入草稿节点，
     * 最后交给人工且不发布；防止无限返工消耗资源。
     */
    @Test
    void reworkStopsAtTwoRoundsAndOftenRejectsEmptyFindings() {
        assertThat(factory.reviewRoute(state(Map.of(
                "draftRound", 0,
                "reviewResult", "{\"verdict\":\"REVISE\",\"reviewedDrafts\":[{\"draftId\":19,\"revision\":3}],"
                        + "\"findings\":[],\"question\":null,\"summary\":\"s\"}"))))
                .isEqualTo("fix_reviewer");

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
        System.out.println("测试证据：场景=返工上限，draftRound=0->REVISE，draftRound=2->REVISE_LIMIT，空finding=进入修复回路");
    }

    /**
     * 业务目的：模型长 JSON 输出存在把开头字段重复写在结尾的伪影（实测 candidateTargetDocumentId 在首尾各出现一次）。
     * 此时 Jackson 直接解析 record 会因"构造器属性被二次赋值"抛 InvalidDefinitionException，使整个 run 失败；
     * 应容忍重复键（JsonNode 层面 last-wins 覆盖），正常路由。
     */
    @Test
    void duplicateCreatorFieldInModelOutputIsTolerated() {
        String retrieval = "{\"issueType\":\"MISSING\",\"candidateTargetDocumentId\":1,\"facts\":[{\"statement\":\"a\","
                + "\"support\":\"SUPPORTED\",\"sourceRefs\":[]}],\"unresolvedQuestions\":[],\"summary\":\"r\","
                + "\"candidateTargetDocumentId\":6}";
        String route = factory.coordinatorRoute(state(Map.of(
                "stage", "DECIDE",
                "coordinationResult", "{\"stage\":\"DECIDE\",\"action\":\"DRAFT\",\"reason\":\"r\","
                        + "\"draftInstruction\":\"写入背景\",\"question\":null,\"summary\":\"s\"}",
                "retrievalResult", retrieval)));
        assertThat(route).isEqualTo("DRAFT");
        System.out.println("测试证据：场景=模型输出重复键，重复候选文档字段仍可解析并正常路由");
    }

    /**
     * 业务目的：双通道契约下主 Agent 的 CHAT/TURN_DONE 必须携带可见回复（正文非空）或 memo 回退；
     * 两者皆缺、或输出不含尾部 JSON（提取器判定内容不完整）时必须进入修复回路（fix_main_agent），
     * 防止结束本轮却没有给管理员任何可回应的实质内容（D3 校验改造 + spec 双通道场景）。
     */
    @Test
    void mainChatAndTurnDoneRequireVisibleReplyPerDualChannel() {
        // 合法：正文 + 尾部 JSON 尾缀（双通道标准形态）。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "你好，我在线。\n{\"action\":\"CHAT\",\"expertCalls\":[]}"))))
                .isEqualTo("CHAT");
        // 合法：正文缺失但尾缀含 memo → 允许以极短降级摘要结束本轮。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "{\"action\":\"TURN_DONE\",\"expertCalls\":[],\"memo\":\"已整理的极短摘要\"}"))))
                .isEqualTo("TURN_DONE");
        // 非法：正文与 memo 双双缺失 → 进入修复回路（无可见回复）。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "{\"action\":\"CHAT\",\"expertCalls\":[]}"))))
                .isEqualTo("fix_main_agent");
        // 非法：memo 为空白等同于缺失。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "{\"action\":\"CHAT\",\"expertCalls\":[],\"memo\":\"   \"}"))))
                .isEqualTo("fix_main_agent");
        // 非法：只有正文没有尾部 JSON（无法解析出 action）→ 结构化输出校验不过，进入修复回路。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "你好，我在线。"))))
                .isEqualTo("fix_main_agent");
        // 非法：正文缺失且 memo 达到上限（runId=68 实测：模型把完整回复塞进 memo，被 100 码点防御截断成半句）。
        // memo 触顶是「模型未遵守双通道、把回复写进结构化字段」的强信号，必须回炉重写正文而非展示半句。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "{\"action\":\"CHAT\",\"expertCalls\":[],\"memo\":\"" + runId68LongMemo + "\"}"))))
                .isEqualTo("fix_main_agent");
        // 合法边界：正文缺失但 memo 未触顶（真正的极短摘要）→ 仍按降级语义放行。
        assertThat(factory.mainRoute(state(Map.of(
                "mainTurnResult", "{\"action\":\"CHAT\",\"expertCalls\":[],\"memo\":\"请查看上轮结论。\"}"))))
                .isEqualTo("CHAT");
        System.out.println("测试证据：场景=主Agent双通道可见回复校验，正文/memo 有其一可结束，两者皆缺或JSON缺失=修复回路");
    }
}
