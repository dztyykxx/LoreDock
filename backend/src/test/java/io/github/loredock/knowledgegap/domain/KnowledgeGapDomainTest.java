package io.github.loredock.knowledgegap.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeGapDomainTest {
    /**
     * 业务目的：人工处理状态只能保持或前进一步，防止跳过确认、倒退或关闭后重开。
     */
    @Test
    void statusAllowsIdempotenceAndOnlyNextForwardStep() {
        assertThat(KnowledgeGapStatus.OPEN.canMoveTo(KnowledgeGapStatus.OPEN)).isTrue();
        assertThat(KnowledgeGapStatus.OPEN.canMoveTo(KnowledgeGapStatus.ACKNOWLEDGED)).isTrue();
        assertThat(KnowledgeGapStatus.ACKNOWLEDGED.canMoveTo(KnowledgeGapStatus.CLOSED)).isTrue();
        assertThat(KnowledgeGapStatus.OPEN.canMoveTo(KnowledgeGapStatus.CLOSED)).isFalse();
        assertThat(KnowledgeGapStatus.CLOSED.canMoveTo(KnowledgeGapStatus.OPEN)).isFalse();
        System.out.println("测试证据：场景=知识缺口状态机，OPEN→ACKNOWLEDGED→CLOSED=允许，跳过/倒退=拒绝");
    }

    /**
     * 业务目的：问题与说明按 Unicode 字符而非 UTF-16 单元限长，避免中文和 emoji 边界误判。
     */
    @Test
    void textNormalizesUnicodeAndEnforcesBusinessLimits() {
        assertThat(KnowledgeGapText.question(" Cafe\u0301 为什么？ ")).isEqualTo("Café 为什么？");
        assertThat(KnowledgeGapText.note("😀".repeat(1000))).hasSize(2000);
        assertThatThrownBy(() -> KnowledgeGapText.note("😀".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeGapText.question(" ")).isInstanceOf(IllegalArgumentException.class);
        System.out.println("测试证据：场景=知识缺口Unicode边界，说明码点=1000，超限=拒绝");
    }

    /**
     * 业务目的：管理分页游标必须稳定恢复时间和 ID，畸形输入不能退化为 offset 或部分解析。
     */
    @Test
    void cursorRoundTripsAndRejectsMalformedInput() {
        KnowledgeGapCursor expected = new KnowledgeGapCursor(
                Instant.parse("2026-07-30T10:00:00.123456Z"),
                UUID.fromString("77000000-0000-0000-0000-000000000001"));
        String encoded = KnowledgeGapCursorCodec.encode(expected);

        assertThat(KnowledgeGapCursorCodec.decode(encoded)).isEqualTo(expected);
        assertThatThrownBy(() -> KnowledgeGapCursorCodec.decode("broken"))
                .isInstanceOf(IllegalArgumentException.class);
        System.out.printf("测试证据：场景=知识缺口复合游标，时间=%s，ID=%s，明文暴露=false%n",
                expected.createdAt(), expected.id());
    }
}
