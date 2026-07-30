package io.github.loredock.knowledgegap.application;

import io.github.loredock.knowledgegap.domain.KnowledgeGapCursor;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.knowledgegap.domain.KnowledgeGapType;
import io.github.loredock.platform.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManageKnowledgeGapServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-30T12:00:00Z");
    private MemoryRepository repository;
    private ManageKnowledgeGapService service;

    @BeforeEach
    void setUp() {
        repository = new MemoryRepository();
        service = new ManageKnowledgeGapService(repository, () -> NOW);
    }

    /**
     * 业务目的：管理员列表必须在仓储过滤后按复合游标分页，不能把其他项目或状态混入结果。
     */
    @Test
    void listUsesFiltersAndStableCompositeCursor() {
        repository.items.add(record("atlas", KnowledgeGapStatus.OPEN, NOW, 1));
        repository.items.add(record("atlas", KnowledgeGapStatus.OPEN, NOW.minusSeconds(1), 2));
        repository.items.add(record("other", KnowledgeGapStatus.OPEN, NOW.minusSeconds(2), 3));

        KnowledgeGapFeedbackPage page = service.list(new QueryKnowledgeGapsCommand(
                new KnowledgeGapFilter("atlas", null, null, KnowledgeGapStatus.OPEN), null, 1));

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().feedback().projectIdentifier()).isEqualTo("atlas");
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(repository.lastRequestedLimit).isEqualTo(2);
        System.out.printf("测试证据：场景=管理员反馈过滤分页，项目=atlas，状态=OPEN，返回=%d，探测上限=%d%n",
                page.items().size(), repository.lastRequestedLimit);
    }

    /**
     * 业务目的：管理员只能逐步确认并关闭；同状态重试幂等，跳过和倒退保持原审计事实。
     */
    @Test
    void statusMovesForwardOneStepAndSameStateIsIdempotent() {
        KnowledgeGapFeedbackRecord open = record("atlas", KnowledgeGapStatus.OPEN, NOW.minusSeconds(10), 1);
        repository.items.add(open);

        KnowledgeGapFeedbackSnapshot acknowledged = service.updateStatus(
                new UpdateKnowledgeGapStatusCommand("admin", open.id(), KnowledgeGapStatus.ACKNOWLEDGED));
        KnowledgeGapFeedbackSnapshot retried = service.updateStatus(
                new UpdateKnowledgeGapStatusCommand("admin", open.id(), KnowledgeGapStatus.ACKNOWLEDGED));
        KnowledgeGapFeedbackSnapshot closed = service.updateStatus(
                new UpdateKnowledgeGapStatusCommand("admin", open.id(), KnowledgeGapStatus.CLOSED));

        assertThat(acknowledged.feedback().status()).isEqualTo(KnowledgeGapStatus.ACKNOWLEDGED);
        assertThat(retried.feedback().updatedAt()).isEqualTo(acknowledged.feedback().updatedAt());
        assertThat(closed.feedback().status()).isEqualTo(KnowledgeGapStatus.CLOSED);
        assertThat(closed.feedback().updatedBy()).isEqualTo("admin");
        assertThatThrownBy(() -> service.updateStatus(
                new UpdateKnowledgeGapStatusCommand("admin", open.id(), KnowledgeGapStatus.OPEN)))
                .isInstanceOf(KnowledgeGapStatusConflictException.class);
        System.out.printf("测试证据：场景=管理员状态推进，feedbackId=%s，终态=%s，操作者=%s，倒退=409%n",
                open.id(), closed.feedback().status(), closed.feedback().updatedBy());
    }

    /**
     * 业务目的：OPEN 不能直接关闭；并发更新失败后必须复读真实状态，不能报告虚假成功。
     */
    @Test
    void skippedOrLostCompareAndSetReturnsConflict() {
        KnowledgeGapFeedbackRecord open = record("atlas", KnowledgeGapStatus.OPEN, NOW.minusSeconds(10), 1);
        repository.items.add(open);

        assertThatThrownBy(() -> service.updateStatus(
                new UpdateKnowledgeGapStatusCommand("admin", open.id(), KnowledgeGapStatus.CLOSED)))
                .isInstanceOf(KnowledgeGapStatusConflictException.class);
        assertThat(repository.items.getFirst().status()).isEqualTo(KnowledgeGapStatus.OPEN);
        System.out.printf("测试证据：场景=状态跳过，feedbackId=%s，原状态=%s，更新次数=%d%n",
                open.id(), repository.items.getFirst().status(), repository.updateCount);
    }

    private KnowledgeGapFeedbackRecord record(
            String project, KnowledgeGapStatus status, Instant createdAt, int suffix
    ) {
        return new KnowledgeGapFeedbackRecord(
                new UUID(0x7700000000000000L, suffix), "member", "key-" + suffix, "a".repeat(64),
                UUID.randomUUID(), project, UUID.randomUUID(), "main", null, null,
                KnowledgeGapType.NO_ANSWER, status, "为什么？", null, null, null, null,
                createdAt, createdAt, "member", "member");
    }

    private static final class MemoryRepository implements KnowledgeGapFeedbackRepository {
        private final List<KnowledgeGapFeedbackRecord> items = new ArrayList<>();
        private int lastRequestedLimit;
        private int updateCount;

        @Override public boolean insertIfAbsent(KnowledgeGapFeedbackRecord feedback) { return false; }
        @Override public void insertCitations(List<KnowledgeGapCitationRecord> citations) { }
        @Override public Optional<KnowledgeGapFeedbackRecord> findByOperatorAndIdempotencyKey(String operatorId, String key) { return Optional.empty(); }
        @Override public Optional<KnowledgeGapFeedbackRecord> findById(UUID feedbackId) {
            return items.stream().filter(value -> value.id().equals(feedbackId)).findFirst();
        }
        @Override
        public List<KnowledgeGapFeedbackRecord> findAll(KnowledgeGapFilter filter, KnowledgeGapCursor after, int limit) {
            lastRequestedLimit = limit;
            return items.stream()
                    .filter(value -> filter.projectIdentifier() == null
                            || filter.projectIdentifier().equals(value.projectIdentifier()))
                    .filter(value -> filter.status() == null || filter.status() == value.status())
                    .sorted(Comparator.comparing(KnowledgeGapFeedbackRecord::createdAt)
                            .thenComparing(KnowledgeGapFeedbackRecord::id).reversed())
                    .limit(limit).toList();
        }
        @Override public List<KnowledgeGapCitationRecord> findCitations(UUID feedbackId) { return List.of(); }
        @Override
        public boolean updateStatus(UUID feedbackId, KnowledgeGapStatus expected, KnowledgeGapStatus target,
                                    String actor, Instant updatedAt) {
            for (int index = 0; index < items.size(); index++) {
                KnowledgeGapFeedbackRecord current = items.get(index);
                if (current.id().equals(feedbackId) && current.status() == expected) {
                    updateCount++;
                    items.set(index, new KnowledgeGapFeedbackRecord(
                            current.id(), current.operatorId(), current.idempotencyKey(), current.requestHash(),
                            current.projectId(), current.projectIdentifier(), current.branchId(), current.branch(),
                            current.questionId(), current.runId(), current.type(), target, current.question(), current.note(),
                            current.resultType(), current.refusalReason(), current.errorCode(), current.createdAt(),
                            updatedAt, current.createdBy(), actor));
                    return true;
                }
            }
            return false;
        }
    }
}
