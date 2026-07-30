package io.github.loredock.knowledgegap.application;

import io.github.loredock.knowledgegap.domain.KnowledgeGapCursor;
import io.github.loredock.knowledgegap.domain.KnowledgeGapCursorCodec;
import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;
import io.github.loredock.platform.time.TimeProvider;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 数据库内过滤并以比较更新推进人工状态；不调用任何知识或索引写能力。 */
@Service
@Slf4j
public class ManageKnowledgeGapService implements AdminKnowledgeGapUseCase {
    private static final int MAX_PAGE_SIZE = 100;
    private final KnowledgeGapFeedbackRepository feedback;
    private final TimeProvider timeProvider;

    /** @param feedback 反馈仓储 @param timeProvider UTC 时间源 */
    public ManageKnowledgeGapService(KnowledgeGapFeedbackRepository feedback, TimeProvider timeProvider) {
        this.feedback = feedback;
        this.timeProvider = timeProvider;
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeGapFeedbackPage list(QueryKnowledgeGapsCommand command) {
        if (command == null || command.limit() < 1 || command.limit() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("knowledge gap page size is invalid");
        }
        KnowledgeGapFilter filter = normalize(command.filter());
        KnowledgeGapCursor after = command.cursor() == null || command.cursor().isBlank()
                ? null : KnowledgeGapCursorCodec.decode(command.cursor());
        List<KnowledgeGapFeedbackRecord> records = feedback.findAll(filter, after, command.limit() + 1);
        boolean hasMore = records.size() > command.limit();
        List<KnowledgeGapFeedbackRecord> visible = hasMore ? records.subList(0, command.limit()) : records;
        List<KnowledgeGapFeedbackSnapshot> items = new ArrayList<>(visible.size());
        visible.forEach(record -> items.add(snapshot(record)));
        String nextCursor = hasMore && !visible.isEmpty()
                ? KnowledgeGapCursorCodec.encode(new KnowledgeGapCursor(
                        visible.getLast().createdAt(), visible.getLast().id()))
                : null;
        log.info("knowledge_gap admin list traceId={} project={} branch={} type={} status={} resultCount={} hasMore={}",
                traceId(), filter.projectIdentifier(), filter.branch(), filter.type(), filter.status(),
                items.size(), hasMore);
        return new KnowledgeGapFeedbackPage(items, nextCursor);
    }

    @Override
    @Transactional(readOnly = true)
    public KnowledgeGapFeedbackSnapshot detail(UUID feedbackId) {
        KnowledgeGapFeedbackRecord record = feedback.findById(feedbackId)
                .orElseThrow(KnowledgeGapNotFoundException::new);
        return snapshot(record);
    }

    @Override
    @Transactional
    public KnowledgeGapFeedbackSnapshot updateStatus(UpdateKnowledgeGapStatusCommand command) {
        if (command == null || command.actor() == null || command.actor().isBlank()
                || command.feedbackId() == null || command.targetStatus() == null) {
            throw new IllegalArgumentException("knowledge gap status command is invalid");
        }
        KnowledgeGapFeedbackRecord current = feedback.findById(command.feedbackId())
                .orElseThrow(KnowledgeGapNotFoundException::new);
        if (current.status() == command.targetStatus()) {
            return snapshot(current);
        }
        if (!current.status().canMoveTo(command.targetStatus())) {
            log.warn("knowledge_gap status failed traceId={} feedbackId={} from={} to={} "
                            + "errorCode=KNOWLEDGE_GAP_STATUS_CONFLICT",
                    traceId(), current.id(), current.status(), command.targetStatus());
            throw new KnowledgeGapStatusConflictException();
        }
        Instant updatedAt = timeProvider.now();
        if (!feedback.updateStatus(
                current.id(), current.status(), command.targetStatus(), command.actor().strip(), updatedAt)) {
            KnowledgeGapFeedbackRecord concurrent = feedback.findById(current.id())
                    .orElseThrow(KnowledgeGapNotFoundException::new);
            if (concurrent.status() == command.targetStatus()) {
                return snapshot(concurrent);
            }
            throw new KnowledgeGapStatusConflictException();
        }
        KnowledgeGapFeedbackRecord updated = feedback.findById(current.id())
                .orElseThrow(KnowledgeGapNotFoundException::new);
        log.info("knowledge_gap status changed traceId={} feedbackId={} project={} branch={} from={} to={} actor={}",
                traceId(), updated.id(), updated.projectIdentifier(), updated.branch(), current.status(),
                updated.status(), command.actor().strip());
        return snapshot(updated);
    }

    private KnowledgeGapFeedbackSnapshot snapshot(KnowledgeGapFeedbackRecord record) {
        return new KnowledgeGapFeedbackSnapshot(record, feedback.findCitations(record.id()).stream()
                .map(KnowledgeGapCitationRecord::evidenceId).toList());
    }

    private KnowledgeGapFilter normalize(KnowledgeGapFilter filter) {
        if (filter == null) {
            return new KnowledgeGapFilter(null, null, null, null);
        }
        return new KnowledgeGapFilter(optional(filter.projectIdentifier()), optional(filter.branch()),
                filter.type(), filter.status());
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String traceId() {
        String current = MDC.get("traceId");
        return current == null || current.isBlank() ? "background" : current;
    }
}
