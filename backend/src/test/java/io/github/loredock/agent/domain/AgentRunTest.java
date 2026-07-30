package io.github.loredock.agent.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-30T12:00:00Z");

    /**
     * 业务目的：一次运行只能沿 ACCEPTED、RUNNING、终态前进，防止迟到模型结果覆盖已经公开的失败事实。
     */
    @Test
    void terminalRunRejectsLateCompletionAndKeepsFailure() {
        AgentRun run = acceptedRun();

        assertThat(run.start(ACCEPTED_AT.plusSeconds(1))).isTrue();
        assertThat(run.fail(AgentErrorCode.AGENT_MODEL_UNAVAILABLE, ACCEPTED_AT.plusSeconds(2))).isTrue();
        assertThat(run.complete(AgentResultType.ANSWER, "迟到回答", null, ACCEPTED_AT.plusSeconds(3))).isFalse();

        assertThat(run.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(run.errorCode()).isEqualTo(AgentErrorCode.AGENT_MODEL_UNAVAILABLE);
        assertThat(run.resultText()).isNull();
        System.out.printf("测试证据：场景=终态拒绝迟到回答，runId=%s，status=%s，error=%s%n",
                run.id(), run.status(), run.errorCode());
    }

    /**
     * 业务目的：正常回答和可信拒答都属于已完成结果，但必须保留不同结果类型供 Web 正确展示。
     */
    @Test
    void completedRunDistinguishesAnswerFromRefusal() {
        AgentRun answer = acceptedRun();
        answer.start(ACCEPTED_AT.plusSeconds(1));
        answer.complete(AgentResultType.ANSWER, "有依据的回答", null, ACCEPTED_AT.plusSeconds(2));

        AgentRun refusal = acceptedRun();
        refusal.start(ACCEPTED_AT.plusSeconds(1));
        refusal.complete(AgentResultType.REFUSAL, "当前知识库没有足够依据",
                AgentRefusalReason.INSUFFICIENT_EVIDENCE, ACCEPTED_AT.plusSeconds(2));

        assertThat(answer.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(answer.resultType()).isEqualTo(AgentResultType.ANSWER);
        assertThat(refusal.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(refusal.resultType()).isEqualTo(AgentResultType.REFUSAL);
        assertThat(refusal.refusalReason()).isEqualTo(AgentRefusalReason.INSUFFICIENT_EVIDENCE);
        System.out.printf("测试证据：场景=区分回答与拒答，answer=%s，refusal=%s/%s%n",
                answer.resultType(), refusal.resultType(), refusal.refusalReason());
    }

    /**
     * 业务目的：Skill、模型和范围快照必须在接收运行时固定，避免执行中配置更新改变当前运行事实。
     */
    @Test
    void acceptedRunKeepsImmutableVersionAndScopeSnapshot() {
        AgentRun run = acceptedRun();

        assertThat(run.versions().skillVersion()).isEqualTo("1.0.0");
        assertThat(run.scope().branch()).isEqualTo("main");
        assertThat(run.scope().commit()).isEqualTo("abcdef1");
        assertThatThrownBy(() -> run.scope().allowedKnowledgeScopes().add("OTHER"))
                .isInstanceOf(UnsupportedOperationException.class);
        System.out.printf("测试证据：场景=固定运行快照，skill=%s，branch=%s，commit=%s%n",
                run.versions().skillVersion(), run.scope().branch(), run.scope().commit());
    }

    /**
     * 业务目的：模型没有返回 Token 时必须保留未知语义，防止报表把未知用量错误统计为零。
     */
    @Test
    void missingTokenUsageRemainsUnknown() {
        AgentRun run = acceptedRun();
        run.start(ACCEPTED_AT.plusSeconds(1));
        run.updateUsage(2, 3, null, null);
        run.complete(AgentResultType.ANSWER, "回答", null, ACCEPTED_AT.plusSeconds(2));

        assertThat(run.inputTokens()).isNull();
        assertThat(run.outputTokens()).isNull();
        assertThat(run.modelCallCount()).isEqualTo(3);
        System.out.printf("测试证据：场景=Token未知，modelCalls=%d，inputTokens=%s，outputTokens=%s%n",
                run.modelCallCount(), run.inputTokens(), run.outputTokens());
    }

    /**
     * 业务目的：资源上限或超时终止必须形成不可回退的 TERMINATED 事实，且不得先伪装成完成结果。
     */
    @Test
    void acceptedOrRunningRunCanTerminateOnlyOnce() {
        AgentRun run = acceptedRun();

        assertThat(run.terminate(AgentErrorCode.AGENT_RUN_TIMEOUT, ACCEPTED_AT.plusSeconds(10))).isTrue();
        assertThat(run.start(ACCEPTED_AT.plusSeconds(11))).isFalse();
        assertThat(run.fail(AgentErrorCode.AGENT_INTERNAL_ERROR, ACCEPTED_AT.plusSeconds(12))).isFalse();

        assertThat(run.status()).isEqualTo(AgentRunStatus.TERMINATED);
        assertThat(run.errorCode()).isEqualTo(AgentErrorCode.AGENT_RUN_TIMEOUT);
        System.out.printf("测试证据：场景=终止态不可回退，status=%s，error=%s%n",
                run.status(), run.errorCode());
    }

    private AgentRun acceptedRun() {
        UUID runId = UUID.randomUUID();
        return AgentRun.accepted(
                runId,
                "member",
                "project_qa",
                "a".repeat(64),
                "b".repeat(64),
                12,
                new AgentScopeSnapshot(
                        UUID.randomUUID(), "atlas", UUID.randomUUID(), "main", UUID.randomUUID(), "abcdef1",
                        UUID.randomUUID(), java.util.List.of("GLOBAL", "PROJECT", "BRANCH")),
                new AgentVersionSnapshot(
                        UUID.randomUUID(), "project_qa", "1.0.0", "c".repeat(64),
                        "openai-compatible", "MiniMax-M2.7", "project-qa-v1", "readonly-v1", "limits-v1"),
                ACCEPTED_AT
        );
    }
}
