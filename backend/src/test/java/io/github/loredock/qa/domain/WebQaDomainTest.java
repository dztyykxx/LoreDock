package io.github.loredock.qa.domain;

import io.github.loredock.agent.domain.AgentErrorCode;
import io.github.loredock.agent.domain.AgentRefusalReason;
import io.github.loredock.agent.domain.AgentResultType;
import io.github.loredock.agent.domain.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebQaDomainTest {

    /**
     * 业务目的：只有可信完成回答才能显示为“有可靠依据”，防止活动或失败运行被页面误标成可信回答。
     */
    @Test
    void runFactsMapToFiveStableWebTrustStates() {
        assertThat(WebQaTrustState.from(
                AgentRunStatus.COMPLETED, AgentResultType.ANSWER, null, null))
                .isEqualTo(WebQaTrustState.RELIABLE_ANSWER);
        assertThat(WebQaTrustState.from(
                AgentRunStatus.COMPLETED, AgentResultType.REFUSAL,
                AgentRefusalReason.SOURCE_CONFLICT, null))
                .isEqualTo(WebQaTrustState.SOURCE_CONFLICT);
        assertThat(WebQaTrustState.from(
                AgentRunStatus.COMPLETED, AgentResultType.REFUSAL,
                AgentRefusalReason.INSUFFICIENT_EVIDENCE, null))
                .isEqualTo(WebQaTrustState.INSUFFICIENT_EVIDENCE);
        assertThat(WebQaTrustState.from(AgentRunStatus.RUNNING, null, null, null))
                .isEqualTo(WebQaTrustState.IN_PROGRESS);
        assertThat(WebQaTrustState.from(
                AgentRunStatus.FAILED, null, null, AgentErrorCode.AGENT_MODEL_UNAVAILABLE))
                .isEqualTo(WebQaTrustState.FAILED);
        assertThat(WebQaTrustState.from(
                AgentRunStatus.TERMINATED, null, null, AgentErrorCode.AGENT_RUN_TIMEOUT))
                .isEqualTo(WebQaTrustState.FAILED);
        System.out.println("测试证据：场景=Web可信状态映射，完成回答=RELIABLE_ANSWER，冲突/拒答/活动/失败均独立");
    }

    /**
     * 业务目的：不一致的运行终态必须安全失败，防止数据库损坏或适配错误被渲染成可信内容。
     */
    @Test
    void inconsistentTerminalFactsAreRejected() {
        assertThatThrownBy(() -> WebQaTrustState.from(
                AgentRunStatus.COMPLETED, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> WebQaTrustState.from(
                AgentRunStatus.FAILED, AgentResultType.ANSWER, null, null))
                .isInstanceOf(IllegalStateException.class);
        System.out.println("测试证据：场景=不一致终态拒绝，缺失结果与失败携带答案均未映射");
    }

    /**
     * 业务目的：问题和幂等键必须按 Unicode code point 限制并稳定规范化，防止组合字符绕过边界或摘要漂移。
     */
    @Test
    void questionAndIdempotencyKeyNormalizeUnicodeAndEnforceBoundaries() {
        WebQaQuestionText normalized = WebQaQuestionText.of("  Cafe\u0301 为什么？  ");
        assertThat(normalized.value()).isEqualTo("Café 为什么？");
        assertThat(WebQaQuestionText.of("问".repeat(2000)).codePointLength()).isEqualTo(2000);
        assertThat(WebQaIdempotencyKey.of("  client-一  ").value()).isEqualTo("client-一");

        assertThatThrownBy(() -> WebQaQuestionText.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebQaQuestionText.of("问".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebQaIdempotencyKey.of("键".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.printf("测试证据：场景=Unicode输入边界，规范化问题=%s，上限字符=%d，幂等键上限=%d%n",
                normalized.value(), WebQaQuestionText.MAX_CODE_POINTS, WebQaIdempotencyKey.MAX_CODE_POINTS);
    }

    /**
     * 业务目的：历史分页游标必须无损固定时间与 ID 且拒绝伪造输入，防止 offset 漂移或解析异常泄露内部格式。
     */
    @Test
    void opaqueCursorRoundTripsAndRejectsMalformedValues() {
        WebQaCursor cursor = new WebQaCursor(
                Instant.parse("2026-07-30T03:04:05.123456Z"),
                UUID.fromString("71000000-0000-0000-0000-000000000001"));

        String encoded = WebQaCursorCodec.encode(cursor);
        WebQaCursor decoded = WebQaCursorCodec.decode(encoded);

        assertThat(decoded).isEqualTo(cursor);
        assertThat(encoded).doesNotContain("2026-07-30", cursor.id().toString());
        assertThatThrownBy(() -> WebQaCursorCodec.decode("not-a-cursor"))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.printf("测试证据：场景=不透明游标，时间=%s，ID=%s，明文暴露=false%n",
                decoded.createdAt(), decoded.id());
    }
}
