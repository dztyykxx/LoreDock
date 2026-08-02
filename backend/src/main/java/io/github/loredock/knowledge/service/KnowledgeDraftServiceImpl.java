package io.github.loredock.knowledge.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.knowledge.api.KnowledgeDraftException;
import io.github.loredock.knowledge.api.KnowledgeDraftService;
import io.github.loredock.knowledge.mapper.KnowledgeDraftMapper;
import io.github.loredock.knowledge.mapper.KnowledgeDraftRevisionMapper;
import io.github.loredock.knowledge.mapper.KnowledgeDraftRevisionSourceMapper;
import io.github.loredock.knowledge.model.KnowledgeDocument;
import io.github.loredock.knowledge.model.DocumentAudit;
import io.github.loredock.knowledge.model.DocumentBody;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.DocumentTitle;
import io.github.loredock.knowledge.model.KnowledgeDocumentFields;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.PublishKnowledgeDocumentCommand;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftRevisionEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftRevisionSourceEntity;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.result.KnowledgeDocumentView;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本化草稿实现。更新事务先锁定草稿，再校验基础修订、结构化区块和来源，最后原子提交不可变修订；
 * 相同 run/幂等键重放优先返回原修订，绝不制造重复副作用。
 */
@Service
@Slf4j
public class KnowledgeDraftServiceImpl implements KnowledgeDraftService {

    private static final int MAX_OPERATIONS = 20;
    private static final int MAX_MARKDOWN_CODE_POINTS = 50_000;
    private static final int MAX_DIFF_CODE_POINTS = 20_000;
    private final ProjectService projects;
    private final KnowledgeDocumentDataService documents;
    private final KnowledgeDocumentLifecycleService lifecycle;
    private final KnowledgeDraftMapper drafts;
    private final KnowledgeDraftRevisionMapper revisions;
    private final KnowledgeDraftRevisionSourceMapper sources;
    private final KnowledgeIndexJobService indexJobs;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param projects 项目范围契约
     * @param documents 正式知识基线读取
     * @param lifecycle 既有正式知识发布事务
     * @param drafts 草稿 Mapper
     * @param revisions 修订 Mapper
     * @param sources 修订来源 Mapper
     * @param indexJobs 发布后的知识索引更新任务
     * @param objectMapper 稳定区块 JSON 编解码器
     * @param clock UTC 时间源
     */
    public KnowledgeDraftServiceImpl(
            ProjectService projects,
            KnowledgeDocumentDataService documents,
            KnowledgeDocumentLifecycleService lifecycle,
            KnowledgeDraftMapper drafts,
            KnowledgeDraftRevisionMapper revisions,
            KnowledgeDraftRevisionSourceMapper sources,
            KnowledgeIndexJobService indexJobs,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.projects = projects;
        this.documents = documents;
        this.lifecycle = lifecycle;
        this.drafts = drafts;
        this.revisions = revisions;
        this.sources = sources;
        this.indexJobs = indexJobs;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public DraftRevision create(CreateRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        String key = text(request.idempotencyKey(), 128);
        ProjectScope project = project(context.projectIdentifier());
        KnowledgeDocument baseline = request.baselineDocumentId() == null ? null
                : baseline(request.baselineDocumentId(), project.projectId());
        String title = baseline == null ? text(request.title(), 255) : baseline.fields().title().value();
        String directory = baseline == null ? directory(request.directory()) : baseline.fields().directory().value();
        if (baseline == null && !documents.projectDirectoryExists(project.projectId(), directory)) {
            throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
        }
        Long baselineRevision = baseline == null ? null : baseline.revision().value();
        WorkspaceOperation operation = baseline == null ? WorkspaceOperation.ADD : WorkspaceOperation.MODIFY;
        String requestHash = hash(title + "\n" + directory + "\n"
                + Objects.toString(request.baselineDocumentId(), "") + "\n"
                + Objects.toString(baselineRevision, ""));
        KnowledgeDraftEntity replay = drafts.selectOne(Wrappers.<KnowledgeDraftEntity>lambdaQuery()
                .eq(KnowledgeDraftEntity::getCreateRunId, context.runId())
                .eq(KnowledgeDraftEntity::getCreateIdempotencyKey, key));
        if (replay != null) {
            if (!requestHash.equals(replay.getCreateRequestHash())) {
                throw failure(KnowledgeDraftException.Code.DRAFT_IDEMPOTENCY_CONFLICT);
            }
            return revision(replay, requireRevision(replay.getId(), 0));
        }
        long workspaceCount = drafts.selectCount(Wrappers.<KnowledgeDraftEntity>lambdaQuery()
                .eq(KnowledgeDraftEntity::getConversationId, context.conversationId())
                .eq(KnowledgeDraftEntity::getOperatorId, context.operatorId()));
        if (workspaceCount >= 10) {
            throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
        }
        String markdown = baseline == null ? "" : baseline.fields().body().value();
        List<DraftBlock> blocks = markdown.isEmpty()
                ? List.of() : List.of(new DraftBlock("b-baseline", markdown));
        Instant now = clock.instant();
        KnowledgeDraftEntity entity = KnowledgeDraftEntity.builder()
                .conversationId(context.conversationId()).operatorId(context.operatorId())
                .projectId(project.projectId()).projectIdentifier(project.projectIdentifier())
                .title(title).operation(operation.name()).directoryPath(directory)
                .baselineDocumentId(request.baselineDocumentId()).baselineRevision(baselineRevision)
                .currentRevision(0L)
                .createRunId(context.runId()).createIdempotencyKey(key).createRequestHash(requestHash)
                .createdAt(now).updatedAt(now).build();
        drafts.insert(entity);
        Long draftId = Objects.requireNonNull(entity.getId(), "草稿写入后未回填主键");
        KnowledgeDraftRevisionEntity initial = KnowledgeDraftRevisionEntity.builder()
                .draftId(draftId).revision(0L).markdown(markdown).blocksJson(json(blocks))
                .changeSummary("初始基线").createdAt(now).build();
        revisions.insert(initial);
        if (drafts.attachConversationDraft(
                context.conversationId(), context.operatorId(), project.projectId(), draftId, now) != 1) {
            throw failure(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION);
        }
        log.info("knowledge_draft created draftId={} conversationId={} runId={} project={} baselineDocumentId={}",
                draftId, context.conversationId(), context.runId(), project.projectIdentifier(),
                request.baselineDocumentId());
        return revision(entity, initial);
    }

    @Override
    @Transactional(readOnly = true)
    public DraftRevision read(ReadRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        KnowledgeDraftEntity draft = visible(request.draftId(), context);
        long number = request.revision() == null ? draft.getCurrentRevision() : request.revision();
        return revision(draft, requireRevision(draft.getId(), number));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DraftRevision> list(ReadRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        KnowledgeDraftEntity draft = visible(request.draftId(), context);
        return revisions.selectList(Wrappers.<KnowledgeDraftRevisionEntity>lambdaQuery()
                        .eq(KnowledgeDraftRevisionEntity::getDraftId, draft.getId())
                        .orderByAsc(KnowledgeDraftRevisionEntity::getRevision))
                .stream()
                .map(value -> revision(draft, value))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkspaceDocument> listWorkspace(AccessContext value) {
        AccessContext context = requireContext(value);
        return drafts.selectList(Wrappers.<KnowledgeDraftEntity>lambdaQuery()
                        .eq(KnowledgeDraftEntity::getConversationId, context.conversationId())
                        .eq(KnowledgeDraftEntity::getOperatorId, context.operatorId())
                        .eq(KnowledgeDraftEntity::getProjectIdentifier, context.projectIdentifier())
                        .orderByAsc(KnowledgeDraftEntity::getId))
                .stream().map(draft -> {
                    KnowledgeDraftRevisionEntity latest = revisions.selectOne(
                            Wrappers.<KnowledgeDraftRevisionEntity>lambdaQuery()
                                    .eq(KnowledgeDraftRevisionEntity::getDraftId, draft.getId())
                                    .isNotNull(KnowledgeDraftRevisionEntity::getCreatedByRunId)
                                    .orderByDesc(KnowledgeDraftRevisionEntity::getRevision)
                                    .last("limit 1"));
                    return new WorkspaceDocument(
                            draft.getId(), WorkspaceOperation.valueOf(draft.getOperation()),
                            draft.getBaselineDocumentId(), draft.getBaselineRevision(), draft.getTitle(),
                            draft.getDirectoryPath(), draft.getCurrentRevision(),
                            latest == null ? draft.getCreateRunId() : latest.getCreatedByRunId());
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RunPatchSet patchSet(AccessContext value, Long requestedRunId) {
        AccessContext context = requireContext(value);
        Long runId = positive(requestedRunId);
        List<RunDocumentChange> changes = new ArrayList<>();
        for (KnowledgeDraftEntity draft : drafts.selectList(Wrappers.<KnowledgeDraftEntity>lambdaQuery()
                .eq(KnowledgeDraftEntity::getConversationId, context.conversationId())
                .eq(KnowledgeDraftEntity::getOperatorId, context.operatorId())
                .orderByAsc(KnowledgeDraftEntity::getId))) {
            List<KnowledgeDraftRevisionEntity> runRevisions = revisions.selectList(
                    Wrappers.<KnowledgeDraftRevisionEntity>lambdaQuery()
                            .eq(KnowledgeDraftRevisionEntity::getDraftId, draft.getId())
                            .eq(KnowledgeDraftRevisionEntity::getCreatedByRunId, runId)
                            .orderByAsc(KnowledgeDraftRevisionEntity::getRevision));
            if (runRevisions.isEmpty()) {
                continue;
            }
            long fromRevision = Math.max(0, runRevisions.getFirst().getRevision() - 1);
            long toRevision = runRevisions.getLast().getRevision();
            String from = requireRevision(draft.getId(), fromRevision).getMarkdown();
            String to = requireRevision(draft.getId(), toRevision).getMarkdown();
            int additions = lineCount(to);
            int deletions = lineCount(from);
            changes.add(new RunDocumentChange(
                    draft.getId(), WorkspaceOperation.valueOf(draft.getOperation()), draft.getTitle(),
                    fromRevision, toRevision, additions, deletions));
        }
        return new RunPatchSet(runId, changes,
                changes.stream().mapToInt(RunDocumentChange::additions).sum(),
                changes.stream().mapToInt(RunDocumentChange::deletions).sum());
    }

    @Override
    @Transactional
    public DraftRevision update(UpdateRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        Long draftId = positive(request.draftId());
        String key = text(request.idempotencyKey(), 128);
        String summary = text(request.changeSummary(), 1000);
        if (request.operations().isEmpty() || request.operations().size() > MAX_OPERATIONS) {
            throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
        }
        String requestHash = hash(canonical(request));
        KnowledgeDraftRevisionEntity replay = revisions.selectOne(
                Wrappers.<KnowledgeDraftRevisionEntity>lambdaQuery()
                        .eq(KnowledgeDraftRevisionEntity::getDraftId, draftId)
                        .eq(KnowledgeDraftRevisionEntity::getCreatedByRunId, context.runId())
                        .eq(KnowledgeDraftRevisionEntity::getIdempotencyKey, key));
        if (replay != null) {
            if (!requestHash.equals(replay.getRequestHash())) {
                throw failure(KnowledgeDraftException.Code.DRAFT_IDEMPOTENCY_CONFLICT);
            }
            return revision(visible(draftId, context), replay);
        }
        KnowledgeDraftEntity draft = locked(draftId, context);
        if (draft.getCurrentRevision() != request.baseRevision()) {
            throw failure(KnowledgeDraftException.Code.DRAFT_REVISION_CONFLICT);
        }
        KnowledgeDraftRevisionEntity base = requireRevision(draftId, request.baseRevision());
        List<DraftBlock> blocks = new ArrayList<>(blocks(base));
        Set<SourceRef> usedSources = new LinkedHashSet<>();
        for (UpdateOperation operation : request.operations()) {
            apply(blocks, operation, usedSources);
        }
        String markdown = blocks.stream().map(DraftBlock::markdown).reduce(
                (left, right) -> left.stripTrailing() + "\n\n" + right.stripLeading()).orElse("");
        if (markdown.codePointCount(0, markdown.length()) > MAX_MARKDOWN_CODE_POINTS) {
            throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
        }
        long next = request.baseRevision() + 1;
        Instant now = clock.instant();
        KnowledgeDraftRevisionEntity created = KnowledgeDraftRevisionEntity.builder()
                .draftId(draftId).revision(next).markdown(markdown).blocksJson(json(blocks))
                .changeSummary(summary).createdByRunId(context.runId()).idempotencyKey(key)
                .requestHash(requestHash).createdAt(now).build();
        revisions.insert(created);
        for (SourceRef source : usedSources.stream()
                .sorted(Comparator.comparing((SourceRef value) -> value.type().name())
                        .thenComparing(SourceRef::sourceId)).toList()) {
            sources.insert(KnowledgeDraftRevisionSourceEntity.builder()
                    .revisionId(created.getId()).sourceType(source.type().name()).sourceId(source.sourceId()).build());
        }
        if (drafts.advanceRevision(draftId, request.baseRevision(), next, now) != 1) {
            throw failure(KnowledgeDraftException.Code.DRAFT_REVISION_CONFLICT);
        }
        draft.setCurrentRevision(next);
        draft.setUpdatedAt(now);
        log.info("knowledge_draft updated draftId={} conversationId={} runId={} fromRevision={} toRevision={} "
                        + "operationCount={} sourceCount={}",
                draftId, context.conversationId(), context.runId(), request.baseRevision(), next,
                request.operations().size(), usedSources.size());
        return revision(draft, created);
    }

    @Override
    @Transactional(readOnly = true)
    public DraftDiff diff(DiffRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        KnowledgeDraftEntity draft = visible(request.draftId(), context);
        String from = request.fromRevision() == null
                ? baselineMarkdown(draft.getBaselineDocumentId(), draft.getProjectId())
                : requireRevision(draft.getId(), request.fromRevision()).getMarkdown();
        KnowledgeDraftRevisionEntity toRevision = requireRevision(draft.getId(), request.toRevision());
        String to = toRevision.getMarkdown();
        int deletions = lineCount(from);
        int additions = lineCount(to);
        String value = unified(from, to, request.fromRevision(), request.toRevision());
        boolean truncated = value.codePointCount(0, value.length()) > MAX_DIFF_CODE_POINTS;
        if (truncated) {
            value = value.substring(0, value.offsetByCodePoints(0, MAX_DIFF_CODE_POINTS));
        }
        return new DraftDiff(draft.getId(), request.fromRevision(), request.toRevision(),
                value, additions, deletions, truncated);
    }

    @Override
    @Transactional
    public Publication publish(PublishRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        KnowledgeDraftEntity draft = locked(request.draftId(), context);
        if (draft.getCurrentRevision() != request.reviewedRevision()) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        if (draft.getPublishedDocumentId() != null) {
            if (draft.getPublishedRevision() == request.reviewedRevision()) {
                KnowledgeDocument published = documents.findById(draft.getPublishedDocumentId())
                        .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT));
                return new Publication(draft.getId(), request.reviewedRevision(), published.id(),
                        published.publishedAt());
            }
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        KnowledgeDraftRevisionEntity reviewed = requireRevision(draft.getId(), request.reviewedRevision());
        KnowledgeDocumentFields fields = publicationFields(draft, reviewed);
        Instant now = clock.instant();
        KnowledgeDocument candidate = documents.insertDraft(fields, new DocumentAudit(now, context.operatorId()));
        // 草稿正文只能经管理员入口调用本方法进入正式生命周期；Agent Tool 候选集合从不包含 publish。
        KnowledgeDocumentView published = lifecycle.publish(
                new PublishKnowledgeDocumentCommand(candidate.id(), draft.getBaselineDocumentId()));
        Instant publishedAt = published.publishedAt() == null ? now : published.publishedAt();
        if (drafts.markPublished(draft.getId(), request.reviewedRevision(), published.id(), publishedAt) != 1) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        // 合并草稿的人工发布是闭环的最后一步；直接提交 single-flight 索引更新，
        // 避免演示时还需管理员返回文档页手动触发。
        indexJobs.submit();
        log.info("knowledge_draft published draftId={} conversationId={} operatorId={} revision={} documentId={} "
                        + "baselineDocumentId={}",
                draft.getId(), context.conversationId(), context.operatorId(), request.reviewedRevision(),
                published.id(), draft.getBaselineDocumentId());
        return new Publication(draft.getId(), request.reviewedRevision(), published.id(), publishedAt);
    }

    @Override
    @Transactional
    public WorkspacePublication publishWorkspace(WorkspacePublishRequest request) {
        AccessContext context = requireContext(request == null ? null : request.context());
        List<ReviewedDraft> reviewed = request.reviewedDrafts().stream()
                .sorted(Comparator.comparing(ReviewedDraft::draftId)).toList();
        if (reviewed.isEmpty() || reviewed.size() > 10
                || reviewed.stream().anyMatch(value -> value == null || value.draftId() == null
                        || value.draftId() <= 0 || value.reviewedRevision() <= 0)
                || reviewed.stream().map(ReviewedDraft::draftId).distinct().count() != reviewed.size()) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        List<KnowledgeDraftEntity> lockedDrafts = reviewed.stream()
                .map(value -> locked(value.draftId(), context)).toList();
        List<WorkspaceDocument> changedWorkspace = listWorkspace(context).stream()
                .filter(document -> document.currentRevision() > 0).toList();
        if (changedWorkspace.size() != reviewed.size()) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        Map<Long, Long> reviewedRevisions = reviewed.stream().collect(
                java.util.stream.Collectors.toMap(ReviewedDraft::draftId, ReviewedDraft::reviewedRevision));
        if (changedWorkspace.stream().anyMatch(document -> !Objects.equals(
                reviewedRevisions.get(document.draftId()), document.currentRevision()))) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        List<Long> baselineIds = lockedDrafts.stream().map(KnowledgeDraftEntity::getBaselineDocumentId)
                .filter(Objects::nonNull).distinct().sorted().toList();
        Map<Long, KnowledgeDocument> baselines = documents.findAllByIdsForUpdate(baselineIds).stream()
                .collect(java.util.stream.Collectors.toMap(KnowledgeDocument::id, value -> value));
        if (baselines.size() != baselineIds.size()) {
            throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
        }
        Instant now = clock.instant();
        DocumentAudit audit = new DocumentAudit(now, context.operatorId());
        List<Publication> publications = new ArrayList<>();
        for (KnowledgeDraftEntity draft : lockedDrafts) {
            long revision = reviewedRevisions.get(draft.getId());
            if (!Objects.equals(draft.getCurrentRevision(), revision) || draft.getPublishedDocumentId() != null) {
                throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
            }
            KnowledgeDraftRevisionEntity reviewedRevision = requireRevision(draft.getId(), revision);
            KnowledgeDocument published;
            if (WorkspaceOperation.ADD.name().equals(draft.getOperation())) {
                if (!documents.projectDirectoryExists(draft.getProjectId(), draft.getDirectoryPath())
                        || documents.existsPublishedProjectTitle(
                                draft.getProjectId(), draft.getDirectoryPath(), draft.getTitle())) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
                }
                KnowledgeDocument candidate = documents.insertDraft(publicationFields(draft, reviewedRevision), audit);
                published = candidate.publish(audit);
                if (!documents.update(published, candidate.revision())) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
                }
            } else {
                KnowledgeDocument baseline = baselines.get(draft.getBaselineDocumentId());
                if (baseline == null || !Objects.equals(baseline.revision().value(), draft.getBaselineRevision())) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
                }
                KnowledgeDocumentFields updatedFields = new KnowledgeDocumentFields(
                        baseline.fields().format(), baseline.fields().title(),
                        new DocumentBody(reviewedRevision.getMarkdown()), baseline.fields().directory(),
                        baseline.fields().tags(), baseline.fields().source(), baseline.fields().scope());
                published = baseline.edit(updatedFields, audit);
                if (!documents.update(published, baseline.revision())) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
                }
            }
            Instant publishedAt = published.publishedAt() == null ? now : published.publishedAt();
            if (drafts.markPublished(draft.getId(), revision, published.id(), publishedAt) != 1) {
                throw failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT);
            }
            publications.add(new Publication(draft.getId(), revision, published.id(), publishedAt));
        }
        indexJobs.submit();
        log.info("knowledge_workspace published conversationId={} operatorId={} documentCount={}",
                context.conversationId(), context.operatorId(), publications.size());
        return new WorkspacePublication(publications, now);
    }

    private KnowledgeDocumentFields publicationFields(
            KnowledgeDraftEntity draft,
            KnowledgeDraftRevisionEntity reviewed
    ) {
        KnowledgeDocument baseline = draft.getBaselineDocumentId() == null ? null
                : documents.findById(draft.getBaselineDocumentId())
                        .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_PUBLICATION_CONFLICT));
        DocumentDirectory directory = baseline == null
                ? new DocumentDirectory(draft.getDirectoryPath()) : baseline.fields().directory();
        DocumentTags tags = baseline == null ? DocumentTags.of(List.of()) : baseline.fields().tags();
        return new KnowledgeDocumentFields(
                DocumentFormat.MARKDOWN,
                new DocumentTitle(draft.getTitle()),
                new DocumentBody(reviewed.getMarkdown()),
                directory,
                tags,
                new DocumentSource(DocumentSourceType.MANUAL, null, null,
                        "知识任务草稿修订 " + reviewed.getRevision()),
                KnowledgeScope.project(draft.getProjectId()));
    }

    private void apply(List<DraftBlock> blocks, UpdateOperation operation, Set<SourceRef> usedSources) {
        if (operation == null || operation.type() == null || operation.sourceRefs().size() > 20
                || operation.sourceRefs().stream().anyMatch(value -> value == null || value.type() == null
                        || value.sourceId() == null || value.sourceId() <= 0)) {
            throw failure(KnowledgeDraftException.Code.DRAFT_SOURCE_INVALID);
        }
        usedSources.addAll(operation.sourceRefs());
        int target = indexOf(blocks, operation.targetBlockId());
        switch (operation.type()) {
            case INSERT_AFTER -> {
                String markdown = markdown(operation.markdown());
                int position = operation.targetBlockId() == null ? blocks.size() : target + 1;
                if (operation.targetBlockId() != null && target < 0) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
                }
                blocks.add(position, new DraftBlock("b-" + UUID.randomUUID(), markdown));
            }
            case REPLACE_BLOCK -> {
                if (target < 0) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
                }
                blocks.set(target, new DraftBlock(blocks.get(target).blockId(), markdown(operation.markdown())));
            }
            case DELETE_BLOCK -> {
                if (target < 0 || (operation.markdown() != null && !operation.markdown().isBlank())) {
                    throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
                }
                blocks.remove(target);
            }
        }
    }

    private int indexOf(List<DraftBlock> blocks, String blockId) {
        if (blockId == null) {
            return -1;
        }
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).blockId().equals(blockId)) {
                return index;
            }
        }
        return -1;
    }

    private DraftRevision revision(KnowledgeDraftEntity draft, KnowledgeDraftRevisionEntity value) {
        List<SourceRef> revisionSources = sources.selectList(
                        Wrappers.<KnowledgeDraftRevisionSourceEntity>lambdaQuery()
                                .eq(KnowledgeDraftRevisionSourceEntity::getRevisionId, value.getId())
                                .orderByAsc(KnowledgeDraftRevisionSourceEntity::getSourceType)
                                .orderByAsc(KnowledgeDraftRevisionSourceEntity::getSourceId))
                .stream().map(source -> new SourceRef(
                        SourceType.valueOf(source.getSourceType()), source.getSourceId())).toList();
        return new DraftRevision(
                draft.getId(), value.getRevision(), WorkspaceOperation.valueOf(draft.getOperation()),
                draft.getBaselineDocumentId(), draft.getBaselineRevision(), draft.getTitle(),
                draft.getDirectoryPath(), value.getMarkdown(), blocks(value), revisionSources, value.getChangeSummary(),
                value.getCreatedByRunId(), value.getCreatedAt());
    }

    private List<DraftBlock> blocks(KnowledgeDraftRevisionEntity value) {
        try {
            return objectMapper.readValue(value.getBlocksJson(), new TypeReference<List<DraftBlock>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("草稿区块快照无法读取", exception);
        }
    }

    private String json(List<DraftBlock> blocks) {
        try {
            return objectMapper.writeValueAsString(blocks);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("草稿区块快照无法写入", exception);
        }
    }

    private String canonical(UpdateRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("草稿更新无法规范化", exception);
        }
    }

    private KnowledgeDraftEntity visible(Long draftId, AccessContext context) {
        return Optional.ofNullable(drafts.selectOne(Wrappers.<KnowledgeDraftEntity>lambdaQuery()
                        .eq(KnowledgeDraftEntity::getId, positive(draftId))
                        .eq(KnowledgeDraftEntity::getOperatorId, context.operatorId())
                        .eq(KnowledgeDraftEntity::getProjectIdentifier, context.projectIdentifier())
                        .eq(KnowledgeDraftEntity::getConversationId, context.conversationId())))
                .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_NOT_FOUND));
    }

    private KnowledgeDraftEntity locked(Long draftId, AccessContext context) {
        return Optional.ofNullable(drafts.selectVisibleForUpdate(
                        positive(draftId), context.operatorId(), context.projectIdentifier(), context.conversationId()))
                .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_NOT_FOUND));
    }

    private KnowledgeDraftRevisionEntity requireRevision(Long draftId, long revision) {
        return Optional.ofNullable(revisions.selectOne(Wrappers.<KnowledgeDraftRevisionEntity>lambdaQuery()
                        .eq(KnowledgeDraftRevisionEntity::getDraftId, draftId)
                        .eq(KnowledgeDraftRevisionEntity::getRevision, revision)))
                .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_NOT_FOUND));
    }

    private String baselineMarkdown(Long baselineDocumentId, Long projectId) {
        if (baselineDocumentId == null) {
            return "";
        }
        KnowledgeDocument document = documents.findById(baselineDocumentId)
                .filter(value -> value.fields().scope().projectId() == null
                        || value.fields().scope().projectId().equals(projectId))
                .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION));
        return document.fields().body().value();
    }

    private KnowledgeDocument baseline(Long baselineDocumentId, Long projectId) {
        return documents.findById(baselineDocumentId)
                .filter(value -> value.fields().scope().projectId() == null
                        || value.fields().scope().projectId().equals(projectId))
                .orElseThrow(() -> failure(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION));
    }

    private String directory(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.codePointCount(0, normalized.length()) > 500) {
            throw new IllegalArgumentException("草稿目录参数无效");
        }
        return normalized;
    }

    private ProjectScope project(String identifier) {
        try {
            return projects.resolveEnabledScope(identifier, null);
        } catch (RuntimeException exception) {
            throw failure(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION);
        }
    }

    private AccessContext requireContext(AccessContext context) {
        if (context == null) {
            throw failure(KnowledgeDraftException.Code.DRAFT_SCOPE_VIOLATION);
        }
        return new AccessContext(
                text(context.operatorId(), 128), text(context.projectIdentifier(), 64),
                positive(context.conversationId()), positive(context.runId()));
    }

    private String markdown(String value) {
        String result = text(value, 12_000);
        if (result.codePointCount(0, result.length()) > 12_000) {
            throw failure(KnowledgeDraftException.Code.DRAFT_OPERATION_INVALID);
        }
        return result;
    }

    private String text(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > maximum) {
            throw new IllegalArgumentException("草稿文本参数无效");
        }
        return normalized;
    }

    private Long positive(Long value) {
        if (value == null || value <= 0) {
            throw failure(KnowledgeDraftException.Code.DRAFT_NOT_FOUND);
        }
        return value;
    }

    private String unified(String from, String to, Long fromRevision, long toRevision) {
        StringBuilder result = new StringBuilder()
                .append("--- draft/").append(fromRevision == null ? "baseline" : fromRevision).append('\n')
                .append("+++ draft/").append(toRevision).append('\n')
                .append("@@ -1,").append(lineCount(from)).append(" +1,").append(lineCount(to)).append(" @@\n");
        if (!from.isEmpty()) {
            from.lines().forEach(line -> result.append('-').append(line).append('\n'));
        }
        if (!to.isEmpty()) {
            to.lines().forEach(line -> result.append('+').append(line).append('\n'));
        }
        return result.toString();
    }

    private int lineCount(String value) {
        return value.isEmpty() ? 0 : Math.toIntExact(value.lines().count());
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    private KnowledgeDraftException failure(KnowledgeDraftException.Code code) {
        return new KnowledgeDraftException(code);
    }
}
