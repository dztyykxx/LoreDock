package io.github.loredock.knowledge.converter;

import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.enums.DocumentStatus;
import io.github.loredock.knowledge.model.enums.KnowledgeIndexSyncStatus;
import io.github.loredock.knowledge.model.result.ActiveKnowledgeIndexRevisions;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentSummary;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.knowledge.service.PublishedKnowledgeIndexDataService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把领域聚合映射为应用视图；索引仓储接入前只区分不适用与待首次同步。
 */
@Component
public class KnowledgeDocumentViewFactory {

    private final PublishedKnowledgeIndexDataService indexState;

    /** @param indexState 活动 generation 修订批量读取端口 */
    @Autowired
    public KnowledgeDocumentViewFactory(PublishedKnowledgeIndexDataService indexState) {
        this.indexState = indexState;
    }

    /** 仅供不启动 Spring 的既有领域测试使用，语义等同当前尚无活动 generation。 */
    public KnowledgeDocumentViewFactory() {
        this.indexState = null;
    }

    /**
     * @param document 文档聚合
     * @return 不暴露持久化实体的完整应用视图
     */
    public KnowledgeDocumentView create(KnowledgeDocument document) {
        KnowledgeIndexSyncStatus syncStatus = syncStatuses(List.of(document)).get(document.id());
        return new KnowledgeDocumentView(
                document.id(),
                document.fields().format(),
                document.fields().title().value(),
                document.fields().body().value(),
                document.fields().directory().value(),
                document.fields().tags().values(),
                document.fields().source(),
                document.fields().scope(),
                document.status(),
                document.revision(),
                document.publishedAt(),
                document.publishedBy(),
                document.archivedAt(),
                document.archivedBy(),
                document.replacement(),
                syncStatus,
                document.createdAt(),
                document.updatedAt(),
                document.createdBy(),
                document.updatedBy()
        );
    }

    /**
     * @param document 文档聚合
     * @return 不包含正文和内部持久化信息的列表摘要
     */
    public KnowledgeDocumentSummary summary(KnowledgeDocument document) {
        return summary(document, syncStatuses(List.of(document)).get(document.id()));
    }

    /** 批量派生列表同步状态，避免对同一页逐文档查询活动 generation。 */
    public List<KnowledgeDocumentSummary> summaries(List<KnowledgeDocument> documents) {
        Map<Long, KnowledgeIndexSyncStatus> statuses = syncStatuses(documents);
        return documents.stream().map(document -> summary(document, statuses.get(document.id()))).toList();
    }

    private KnowledgeDocumentSummary summary(
            KnowledgeDocument document,
            KnowledgeIndexSyncStatus syncStatus
    ) {
        return new KnowledgeDocumentSummary(
                document.id(), document.fields().format(), document.fields().title().value(),
                document.fields().directory().value(), document.fields().tags().values(),
                document.fields().source(), document.fields().scope(), document.status(),
                document.revision().value(), syncStatus, document.updatedAt());
    }

    private Map<Long, KnowledgeIndexSyncStatus> syncStatuses(List<KnowledgeDocument> documents) {
        List<Long> publishedIds = documents.stream()
                .filter(document -> document.status() == DocumentStatus.PUBLISHED)
                .map(KnowledgeDocument::id).toList();
        ActiveKnowledgeIndexRevisions active = indexState == null
                ? new ActiveKnowledgeIndexRevisions(false, Map.of())
                : indexState.readActiveRevisions(publishedIds);
        java.util.LinkedHashMap<Long, KnowledgeIndexSyncStatus> statuses = new java.util.LinkedHashMap<>();
        for (KnowledgeDocument document : documents) {
            if (document.status() != DocumentStatus.PUBLISHED) {
                statuses.put(document.id(), KnowledgeIndexSyncStatus.NOT_APPLICABLE);
                continue;
            }
            if (!active.activeGenerationExists()) {
                statuses.put(document.id(), KnowledgeIndexSyncStatus.NEVER_INDEXED);
                continue;
            }
            Long indexedRevision = active.sourceRevisions().get(document.id());
            KnowledgeIndexSyncStatus status = indexedRevision == null
                    ? KnowledgeIndexSyncStatus.PENDING
                    : indexedRevision == document.revision().value()
                    ? KnowledgeIndexSyncStatus.SYNCED
                    : KnowledgeIndexSyncStatus.STALE;
            statuses.put(document.id(), status);
        }
        return Map.copyOf(statuses);
    }
}
