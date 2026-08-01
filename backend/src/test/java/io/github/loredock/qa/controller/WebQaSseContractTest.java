package io.github.loredock.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.api.AgentEvent;
import io.github.loredock.agent.api.AgentRun;
import io.github.loredock.qa.converter.WebQaSseEventMapper;
import io.github.loredock.qa.model.snapshot.WebQaSseCursor;
import io.github.loredock.qa.model.snapshot.WebQaSsePublicEvent;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebQaSseContractTest {
    private static final Long RUN_ID = 6649233113080659970L;
    private static final Instant NOW = Instant.parse("2026-07-30T09:00:00Z");

    /**
     * 业务目的：标准头和查询参数必须表达同一个已消费序号，冲突、负数或非数字均拒绝，防止重复或跳过事件。
     */
    @Test
    void cursorAcceptsEquivalentSourcesAndRejectsAmbiguity() {
        assertThat(WebQaSseCursor.resolve(null, null)).isZero();
        assertThat(WebQaSseCursor.resolve("8", null)).isEqualTo(8);
        assertThat(WebQaSseCursor.resolve(null, 8L)).isEqualTo(8);
        assertThat(WebQaSseCursor.resolve("8", 8L)).isEqualTo(8);
        assertThatThrownBy(() -> WebQaSseCursor.resolve("8", 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebQaSseCursor.resolve("-1", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebQaSseCursor.resolve("not-a-number", null))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.println("测试证据：场景=SSE续读游标，默认=0，头/参数=8，冲突/负数/非数字=拒绝");
    }

    /**
     * 业务目的：阶段与来源事件只能暴露白名单摘要，模型名和原始 payload 不能进入浏览器事件。
     */
    @Test
    void lifecycleAndSourceEventsExposeOnlyFiniteFields() {
        WebQaSsePublicEvent model = map(2, AgentEvent.Type.MODEL_STARTED, "secret-model-name");
        WebQaSsePublicEvent source = map(3, AgentEvent.Type.SOURCE_FOUND, "knowledge_search count=4");

        assertThat(model.name()).isEqualTo("model.started");
        assertThat(model.data().phase()).isEqualTo("GENERATING");
        assertThat(model.toString()).doesNotContain("secret-model-name");
        assertThat(source.name()).isEqualTo("source.found");
        assertThat(source.data().tool()).isEqualTo("knowledge_search");
        assertThat(source.data().count()).isEqualTo(4);
        System.out.printf("测试证据：场景=SSE阶段来源映射，事件=%s/%s，工具=%s，数量=%d，模型名泄露=false%n",
                model.name(), source.name(), source.data().tool(), source.data().count());
    }

    /**
     * 业务目的：完成与失败事件只公开稳定结果类型和错误码，不携带最终正文。
     */
    @Test
    void trustedResultAndTerminalEventsHaveStableNames() {
        WebQaSsePublicEvent completed = map(10, AgentEvent.Type.RUN_COMPLETED, "ANSWER");
        WebQaSsePublicEvent failed = map(11, AgentEvent.Type.RUN_FAILED, "AGENT_MODEL_UNAVAILABLE");

        assertThat(completed.data().resultType()).isEqualTo(AgentRun.ResultType.ANSWER);
        assertThat(completed.data().textDelta()).isNull();
        assertThat(failed.data().errorCode()).isEqualTo(AgentRun.ErrorCode.AGENT_MODEL_UNAVAILABLE);
        assertThat(failed.name()).isEqualTo("run.failed");
        System.out.printf("测试证据：场景=SSE可信终态，完成类型=%s，失败码=%s，正文泄露=false%n",
                completed.data().resultType(), failed.data().errorCode());
    }

    /**
     * 业务目的：Tool、来源与引用校验必须保持服务端事实顺序和主体类型，页面不能根据模型文本伪造完成卡片。
     */
    @Test
    void typedToolSourceAndCitationEventsKeepFactOrderAndSubjects() {
        AgentEvent.Payload startedPayload = payload("RETRIEVING", "knowledge_search", "STARTED", 0);
        AgentEvent.Payload sourcePayload = new AgentEvent.Payload(
                "RETRIEVING", "knowledge_search", "搜索已发布知识", "queryLength=4", "命中 1 篇文档",
                1, 12L, "COMPLETED", List.of(new AgentEvent.Source(
                8000000000000000171L, "审核规则", "PROJECT", "MANUAL", NOW, "HIGH", true, false)),
                null, null, null, null, false, false);
        AgentEvent.Payload validationPayload = payload("VALIDATING", "citation_validator", "PASSED", 1);
        List<WebQaSsePublicEvent> events = List.of(
                WebQaSseEventMapper.toPublic(new AgentEvent(
                        1L, RUN_ID, 3, AgentEvent.Type.TOOL_STARTED, AgentEvent.SubjectType.TOOL,
                        startedPayload, NOW)),
                WebQaSseEventMapper.toPublic(new AgentEvent(
                        2L, RUN_ID, 4, AgentEvent.Type.SOURCE_DISCOVERED, AgentEvent.SubjectType.TOOL,
                        sourcePayload, NOW)),
                WebQaSseEventMapper.toPublic(new AgentEvent(
                        3L, RUN_ID, 5, AgentEvent.Type.CITATION_VALIDATION, AgentEvent.SubjectType.VALIDATOR,
                        validationPayload, NOW)));

        assertThat(events).extracting(WebQaSsePublicEvent::name)
                .containsExactly("tool.started", "source.found", "citation.validation");
        assertThat(events).extracting(event -> event.data().sequence()).containsExactly(3L, 4L, 5L);
        assertThat(events.get(1).data().sources()).extracting(AgentEvent.Source::title).containsExactly("审核规则");
        assertThat(events.get(2).data().subjectType()).isEqualTo(AgentEvent.SubjectType.VALIDATOR);
        System.out.println("测试证据：场景=类型化公开事实，序号=3/4/5，主体=TOOL/TOOL/VALIDATOR，来源数=1");
    }

    /**
     * 业务目的：内部路径、Token 或完整正文即使误入事件对象也必须在进入 SSE 前被拒绝，不能依赖前端隐藏。
     */
    @Test
    void unsafeTypedPayloadIsRejectedBeforeSseProjection() {
        AgentEvent.Payload unsafe = new AgentEvent.Payload(
                "RETRIEVING", "knowledge_search", "搜索知识", "path=/srv/loredock token=secret",
                "完整知识正文：" + "敏感内容".repeat(300), 1, 10L, "COMPLETED", List.of(),
                null, null, null, null, false, false);

        assertThatThrownBy(() -> WebQaSseEventMapper.toPublic(new AgentEvent(
                4L, RUN_ID, 6, AgentEvent.Type.TOOL_COMPLETED, AgentEvent.SubjectType.TOOL, unsafe, NOW)))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.println("测试证据：场景=事件载荷脱敏，内部路径/Token/完整正文=SSE投影拒绝");
    }

    /**
     * 业务目的：伪造工具名、计数、结果类型或错误码必须在服务端失败，不能把任意运行 payload 透传到页面。
     */
    @Test
    void malformedAgentPayloadIsNeverForwarded() {
        assertThatThrownBy(() -> map(1, AgentEvent.Type.SOURCE_FOUND, "shell_exec count=1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> map(2, AgentEvent.Type.SOURCE_FOUND, "knowledge_search count=NaN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> map(3, AgentEvent.Type.RUN_COMPLETED, "MAYBE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> map(4, AgentEvent.Type.RUN_FAILED, "password=secret"))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.println("测试证据：场景=SSE伪造payload，未知工具/计数/结果/错误码均未映射");
    }

    private WebQaSsePublicEvent map(long sequence, AgentEvent.Type type, String payload) {
        return WebQaSseEventMapper.toPublic(new AgentEvent(
                8000000000000000170L, RUN_ID, sequence, type, payload, NOW));
    }

    private AgentEvent.Payload payload(String phase, String name, String status, int count) {
        return new AgentEvent.Payload(
                phase, name, null, null, null, count, null, status,
                List.of(), null, null, null, null, false, false);
    }
}
