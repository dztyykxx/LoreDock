package io.github.loredock.memory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.memory.api.MemoryCandidate;
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryPage;
import io.github.loredock.memory.api.MemoryPageQuery;
import io.github.loredock.memory.api.MemoryEditInput;
import io.github.loredock.memory.api.MemoryDraftInput;
import io.github.loredock.memory.api.MemoryRelevant;
import io.github.loredock.memory.api.MemoryRelevantQuery;
import io.github.loredock.memory.api.MemoryRequestException;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemorySourceType;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.api.MemoryWriteInput;
import io.github.loredock.memory.api.MemoryWriteOutcome;
import io.github.loredock.memory.api.MemoryWriteVerdict;
import io.github.loredock.memory.api.MemoryRevision;
import io.github.loredock.memory.api.MemoryRevisionPage;
import io.github.loredock.memory.api.MemoryWriteDecision;
import io.github.loredock.memory.api.MemoryWriteRelation;
import io.github.loredock.memory.config.MemoryProperties;
import io.github.loredock.memory.mapper.UserMemoryMapper;
import io.github.loredock.memory.mapper.UserMemoryRevisionMapper;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
import io.github.loredock.memory.model.entity.UserMemoryRevisionEntity;
import io.github.loredock.memory.service.MemoryWriteJudger.ExistingMemories;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆业务实现：检索有界化与范围隔离（SQL 层闭合）、全文按需加载计入频次、
 * 提炼写入判断链（新增/重复/增量/冲突 + run 预算）、版本历史与人工管理路径校验。
 *
 * <p>本实现只依赖 {@code memory.api} 契约与 {@code project.api} 稳定范围解析；
 * 记忆只表达用户偏好，不得作为知识证据或检索内容。</p>
 */
public class MemoryServiceImpl implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);

    private static final int PREFILTER_LIMIT = 100;

    private final UserMemoryMapper mapper;
    private final ProjectService projectService;
    private final MemoryWriteJudger judger;
    private final MemoryProperties properties;
    private final Clock clock;
    private final UserMemoryRevisionMapper revisions;
    private final ObjectMapper objectMapper;

    public MemoryServiceImpl(
            UserMemoryMapper mapper,
            ProjectService projectService,
            MemoryWriteJudger judger,
            MemoryProperties properties,
            Clock clock) {
        this(mapper, projectService, judger, properties, clock, null, null);
    }

    public MemoryServiceImpl(
            UserMemoryMapper mapper,
            ProjectService projectService,
            MemoryWriteJudger judger,
            MemoryProperties properties,
            Clock clock,
            UserMemoryRevisionMapper revisions,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.projectService = projectService;
        this.judger = judger;
        this.properties = properties;
        this.clock = clock;
        this.revisions = revisions;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 检索

    @Override
    public List<MemoryRelevant> listRelevant(MemoryRelevantQuery query) {
        int limit = query.limit() <= 0 ? properties.preloadLimit()
                : Math.min(query.limit(), properties.preloadLimit());
        List<String> words = queryWords(query.queryWords());
        List<MemoryRelevant> hits = words.isEmpty() ? List.of() : keywordHits(words, query.projectId(), limit);
        if (hits.isEmpty()) {
            int fallbackLimit = Math.min(properties.fallbackLimit(), limit);
            hits = toRelevant(mapper.selectFallback(query.projectId(), fallbackLimit));
            log.info("记忆预载 无关键词命中触发高频兜底 项目={} 兜底条数={}", query.projectId(), hits.size());
        }
        log.info("记忆预载 项目={} 查询词数={} 返回条数={}", query.projectId(), words.size(), hits.size());
        return hits;
    }

    private List<MemoryRelevant> keywordHits(List<String> words, Long projectId, int limit) {
        Map<Long, UserMemoryEntity> candidates = new LinkedHashMap<>();
        for (String word : words) {
            for (UserMemoryEntity entity : mapper.selectKeywordCandidates(projectId, word, PREFILTER_LIMIT)) {
                candidates.putIfAbsent(entity.getId(), entity);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String word : words) {
            terms.addAll(MemoryRelevanceScorer.tokenize(word));
        }
        List<UserMemoryEntity> ranked = candidates.values().stream()
                .sorted(Comparator
                        .comparingDouble((UserMemoryEntity entity) -> MemoryRelevanceScorer.score(entity, terms))
                        .reversed()
                        .thenComparing(UserMemoryEntity::getLastUsedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserMemoryEntity::getId, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
        return toRelevant(ranked);
    }

    private List<MemoryRelevant> toRelevant(List<UserMemoryEntity> entities) {
        return entities.stream().map(this::toRelevant).toList();
    }

    private MemoryRelevant toRelevant(UserMemoryEntity entity) {
        return new MemoryRelevant(
                entity.getId(),
                MemoryScope.valueOf(entity.getScopeType()),
                entity.getProjectId(),
                entity.getProjectIdentifier(),
                MemoryCategory.valueOf(entity.getCategory()),
                entity.getTitle(),
                bound(entity.getSummary(), properties.summaryMaxLength()),
                entity.getUseCount() == null ? 0L : entity.getUseCount());
    }

    private static List<String> queryWords(List<String> raw) {
        List<String> result = new ArrayList<>();
        for (String word : raw) {
            String text = word == null ? "" : word.strip();
            if (text.isEmpty()) {
                continue;
            }
            text = bound(text, 100);
            String key = text.toLowerCase(Locale.ROOT);
            if (result.stream().noneMatch(existing -> existing.toLowerCase(Locale.ROOT).equals(key))) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    // ------------------------------------------------------------------ 全文

    @Override
    public MemoryFull loadFull(Long memoryId, Long projectId) {
        UserMemoryEntity entity = mapper.selectById(memoryId);
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        if (!MemoryStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在或已停用");
        }
        if (!reachable(entity, projectId)) {
            log.warn("记忆越权加载被拒 id={} 请求项目={} 记忆范围={} 记忆项目={}",
                    memoryId, projectId, entity.getScopeType(), entity.getProjectId());
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_SCOPE_VIOLATION, "记忆对当前会话不可达");
        }
        mapper.touchUse(entity.getId(), clock.instant());
        UserMemoryEntity updated = mapper.selectById(entity.getId());
        log.info("记忆全文加载 id={} 范围={} 频次+1 现在频次={}",
                entity.getId(), entity.getScopeType(), updated.getUseCount());
        return MemoryEntityTransforms.toFull(updated);
    }

    private static boolean reachable(UserMemoryEntity entity, Long projectId) {
        if (MemoryScope.GLOBAL.name().equals(entity.getScopeType())) {
            return true;
        }
        return entity.getProjectId() != null && entity.getProjectId().equals(projectId);
    }

    // ------------------------------------------------------------------ 写入

    @Override
    @Transactional
    public List<MemoryWriteVerdict> acceptWrite(MemoryWriteInput request) {
        requireCandidatesValid(request.candidates());
        if (request.sourceRunId() == null || request.sourceConversationId() == null
                || request.operatorId() == null || request.operatorId().isBlank()) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_FIELD_INVALID, "来源 run、会话与操作者必填");
        }
        MemoryScope scope = request.projectId() == null ? MemoryScope.GLOBAL : MemoryScope.PROJECT;
        ProjectScope project = scope == MemoryScope.PROJECT ? requireEnabledProject(request.projectId()) : null;
        mapper.lockWriteScope(lockKey(scope, request.projectId()));

        // 旧的直接构造测试没有历史 Mapper；生产路径按实际 mutation 计预算，允许满预算请求只产生 SKIP。
        if (revisions == null && mapper.countBySourceRun(request.sourceRunId()) >= properties.writeBudgetPerRun()) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_BUDGET_EXCEEDED,
                    "本 run 记忆修改已达上限，需人工管理后继续");
        }

        List<ExistingMemories> existing = recallNear(scope, request.projectId(), request.candidates());
        Instant now = clock.instant();
        List<MemoryWriteVerdict> verdicts = new ArrayList<>();
        Map<Integer, Long> virtualTargets = new LinkedHashMap<>();
        int mutations = 0;
        long committed = committedMutationCount(request.sourceRunId());
        for (int candidateIndex = 0; candidateIndex < request.candidates().size(); candidateIndex++) {
            MemoryCandidate candidate = request.candidates().get(candidateIndex);
            MemoryWriteJudger.Judgement judgement;
            try {
                judgement = judger.judge(List.of(new MemoryWriteJudger.CandidateForJudgement(
                        0, candidateCategory(candidate), candidate.title(), candidate.summary(), candidate.content())), existing).get(0);
                judgement = copyWithSlot(judgement, candidateIndex);
                validateJudgement(judgement);
            } catch (IllegalStateException exception) {
                log.warn("记忆写入判断模型不可用 run={} candidate={} 原因={}", request.sourceRunId(),
                        verdicts.size(), bounded(exception.getMessage(), 200));
                throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_JUDGE_UNAVAILABLE,
                        "记忆写入判断不可用，请稍后重试");
            }
            MemoryWriteOutcome outcome = judgement.outcome();
            Long targetId = judgement.targetId();
            if (targetId == null && judgement.targetCandidateIndex() != null) {
                targetId = virtualTargets.get(judgement.targetCandidateIndex());
            }
            if (outcome == MemoryWriteOutcome.SKIP_DUPLICATE || outcome == MemoryWriteOutcome.SKIP_DISABLED) {
                UserMemoryEntity target = findExisting(targetId);
                Long targetRevision = target == null ? null
                        : (target.getRevision() == null ? 1L : target.getRevision());
                verdicts.add(verdict(judgement, outcome, null, targetId, targetRevision));
                continue;
            }
            if (outcome == MemoryWriteOutcome.NEEDS_CONFIRMATION || outcome == MemoryWriteOutcome.SKIP_NOT_WORTH) {
                verdicts.add(verdict(judgement, outcome, null, targetId, null));
                continue;
            }
            if (outcome == MemoryWriteOutcome.UPDATED) {
                UserMemoryEntity target = findExisting(targetId);
                if (target == null || !sameScope(target, scope, request.projectId())) {
                    throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "更新目标不在本次范围");
                }
                if (MemoryStatus.DISABLED.name().equals(target.getStatus())) {
                    verdicts.add(verdict(judgement, MemoryWriteOutcome.SKIP_DISABLED, null, target.getId(), target.getRevision()));
                    continue;
                }
                if (judgement.relation() == MemoryWriteRelation.CONFLICT
                        && (request.sourceMessage() == null || judgement.replacementEvidence() == null
                        || !request.sourceMessage().contains(judgement.replacementEvidence()))) {
                    verdicts.add(verdict(judgement, MemoryWriteOutcome.NEEDS_CONFIRMATION, null, target.getId(), target.getRevision()));
                    continue;
                }
                ensureBudget(committed, mutations);
                UserMemoryEntity updated = mergedEntity(target, candidate, judgement, request, now);
                updateMemory(updated, judgement, request, now);
                mutations++;
                verdicts.add(verdict(judgement, outcome, target.getId(), target.getId(), updated.getRevision()));
                existing = replaceExisting(existing, updated);
                continue;
            }
            ensureBudget(committed, mutations);
            Long id = persistMemory(scope, project, candidate, judgement, request, now);
            mutations++;
            verdicts.add(verdict(judgement, outcome, id, null, 1L));
            virtualTargets.put(candidateIndex, id);
            existing = addExisting(existing, mapper.selectById(id));
        }
        return List.copyOf(verdicts);
    }

    private Long persistMemory(MemoryScope scope, ProjectScope project, MemoryCandidate candidate,
            MemoryWriteJudger.Judgement judgement, MemoryWriteInput request, Instant now) {
        String title = judgement.title() == null ? candidate.title() : judgement.title();
        String content = judgement.content() == null ? candidate.content() : judgement.content();
        requireText("记忆标题", title, 1, properties.titleMaxLength());
        requireText("记忆正文", content, 1, properties.contentMaxLength());
        String summary = judgement.summary() != null
                ? bound(judgement.summary(), properties.summaryMaxLength())
                : candidate.summary() != null && !candidate.summary().isBlank()
                ? bound(candidate.summary(), properties.summaryMaxLength())
                : bound(content, properties.summaryMaxLength());
        UserMemoryEntity entity = UserMemoryEntity.builder()
                .scopeType(scope.name())
                .revision(1L)
                .projectId(project == null ? null : project.projectId())
                .projectIdentifier(project == null ? null : project.projectIdentifier())
                .category(candidateCategory(candidate).name())
                .title(title)
                .summary(summary)
                .content(content)
                .sourceType(MemorySourceType.KNOWLEDGE_CURATION.name())
                .sourceRunId(request.sourceRunId())
                .sourceConversationId(request.sourceConversationId())
                .status(MemoryStatus.ACTIVE.name())
                .useCount(0L)
                .createdBy(request.operatorId())
                .updatedBy(request.operatorId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        mapper.insert(entity);
        saveRevision(entity, "CREATE", judgement.relation().name(), judgement.reason(), request.sourceRunId(),
                request.sourceConversationId(), request.sourceMessageId(), request.operatorId(), now);
        log.info("记忆写入 run={} scope={} outcome={} id={} 摘要码点={}",
                request.sourceRunId(), scope, judgement.outcome(), entity.getId(),
                summary.codePointCount(0, summary.length()));
        return entity.getId();
    }

    private Long committedMutationCount(Long sourceRunId) {
        return revisions == null ? mapper.countBySourceRun(sourceRunId) : revisions.countBySourceRun(sourceRunId);
    }

    private MemoryWriteVerdict verdict(MemoryWriteJudger.Judgement judgement, MemoryWriteOutcome outcome,
            Long memoryId, Long targetId, Long revision) {
        return new MemoryWriteVerdict(judgement.slot(), outcome, memoryId, verdictMessage(outcome, judgement),
                judgement.conflictsWith().stream().mapToLong(Long::longValue).toArray(), judgement.relation(), targetId,
                revision, judgement.changes(), judgement.conflicts().stream().map(c -> c.oldClause() + " -> " + c.newClause()).toList(),
                judgement.recommendation(), judgement.question());
    }

    private void ensureBudget(long committed, int mutations) {
        if (committed + mutations + 1 > properties.writeBudgetPerRun()) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_BUDGET_EXCEEDED,
                    "本 run 记忆修改已达上限，需人工管理后继续");
        }
    }

    private UserMemoryEntity findExisting(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    private static boolean sameScope(UserMemoryEntity entity, MemoryScope scope, Long projectId) {
        return scope.name().equals(entity.getScopeType())
                && (scope == MemoryScope.GLOBAL || java.util.Objects.equals(projectId, entity.getProjectId()));
    }

    private static String lockKey(MemoryScope scope, Long projectId) {
        return "loredock:user-memory:" + scope.name() + ":" + (projectId == null ? "GLOBAL" : projectId);
    }

    private static String lockKey(UserMemoryEntity entity) {
        return lockKey(MemoryScope.valueOf(entity.getScopeType()), entity.getProjectId());
    }

    private static List<ExistingMemories> addExisting(List<ExistingMemories> existing, UserMemoryEntity entity) {
        if (entity == null) return existing;
        List<ExistingMemories> result = new ArrayList<>(existing);
        result.add(toExisting(entity));
        return List.copyOf(result);
    }

    private static List<ExistingMemories> replaceExisting(List<ExistingMemories> existing, UserMemoryEntity entity) {
        List<ExistingMemories> result = new ArrayList<>();
        for (ExistingMemories old : existing) result.add(old.id().equals(entity.getId()) ? toExisting(entity) : old);
        return List.copyOf(result);
    }

    private static ExistingMemories toExisting(UserMemoryEntity entity) {
        return new ExistingMemories(entity.getId(), MemoryCategory.valueOf(entity.getCategory()),
                MemoryStatus.valueOf(entity.getStatus()), entity.getTitle(), entity.getSummary(), entity.getContent(),
                entity.getRevision() == null ? 1L : entity.getRevision());
    }

    private static MemoryWriteJudger.Judgement copyWithSlot(MemoryWriteJudger.Judgement judgement, int slot) {
        return new MemoryWriteJudger.Judgement(slot, judgement.relation(), judgement.decision(), judgement.targetId(),
                judgement.targetCandidateIndex(), judgement.title(), judgement.summary(), judgement.content(),
                judgement.reason(), judgement.changes(), judgement.conflicts(), judgement.recommendation(),
                judgement.question(), judgement.replacementEvidence());
    }

    private static void validateJudgement(MemoryWriteJudger.Judgement judgement) {
        boolean valid = switch (judgement.relation()) {
            case NEW -> judgement.decision() == MemoryWriteDecision.CREATE
                    || judgement.decision() == MemoryWriteDecision.SKIP;
            case DUPLICATE -> judgement.decision() == MemoryWriteDecision.SKIP;
            case INCREMENTAL -> judgement.decision() == MemoryWriteDecision.UPDATE;
            case CONFLICT -> judgement.decision() == MemoryWriteDecision.UPDATE
                    || judgement.decision() == MemoryWriteDecision.CONFIRM
                    || judgement.decision() == MemoryWriteDecision.CREATE; // 旧 verdict=CONFLICT_CREATED 兼容
        };
        if (!valid) throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID,
                "记忆判断关系与动作组合非法");
        if (judgement.decision() == MemoryWriteDecision.UPDATE
                && judgement.targetId() == null && judgement.targetCandidateIndex() == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "记忆判断缺少目标");
        }
    }

    private UserMemoryEntity mergedEntity(UserMemoryEntity target, MemoryCandidate candidate,
            MemoryWriteJudger.Judgement judgement, MemoryWriteInput request, Instant now) {
        String title = judgement.title() == null ? target.getTitle() : judgement.title();
        String content = judgement.content();
        requireText("合并标题", title, 1, properties.titleMaxLength());
        requireText("合并正文", content, 1, properties.contentMaxLength());
        String summary = judgement.summary() == null ? bound(content, properties.summaryMaxLength())
                : bound(judgement.summary(), properties.summaryMaxLength());
        target.setCategory(candidateCategory(candidate).name());
        target.setTitle(title);
        target.setSummary(summary);
        target.setContent(content);
        target.setRevision((target.getRevision() == null ? 1L : target.getRevision()) + 1);
        target.setUpdatedAt(now);
        target.setUpdatedBy(request.operatorId());
        return target;
    }

    private void updateMemory(UserMemoryEntity updated, MemoryWriteJudger.Judgement judgement,
            MemoryWriteInput request, Instant now) {
        long expected = updated.getRevision() - 1;
        int affected = mapper.updateMerged(updated.getId(), updated.getCategory(), updated.getTitle(), updated.getSummary(),
                updated.getContent(), updated.getRevision(), expected, updated.getUpdatedAt(), updated.getUpdatedBy());
        if (affected != 1) throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_WRITE_STALE,
                "记忆版本已变化，请刷新后重试");
        saveRevision(updated, "UPDATE", judgement.relation().name(), judgement.reason(), request.sourceRunId(),
                request.sourceConversationId(), request.sourceMessageId(), request.operatorId(), now);
        log.info("记忆合并更新 run={} id={} revision={} relation={}", request.sourceRunId(), updated.getId(),
                updated.getRevision(), judgement.relation());
    }

    private void saveRevision(UserMemoryEntity entity, String operation, String relation, String reason,
            Long sourceRunId, Long sourceConversationId, Long sourceMessageId, String operatorId, Instant now) {
        if (revisions == null) return;
        revisions.insertRevision(UserMemoryRevisionEntity.builder().memoryId(entity.getId())
                .revision(entity.getRevision() == null ? 1L : entity.getRevision()).snapshot(snapshot(entity))
                .operation(operation).relation(relation).reason(bounded(reason, 600)).sourceRunId(sourceRunId)
                .sourceConversationId(sourceConversationId).sourceMessageId(sourceMessageId).operatorId(operatorId)
                .createdAt(now).build());
    }

    private String snapshot(UserMemoryEntity entity) {
        if (objectMapper == null) return "{}";
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("title", entity.getTitle()); value.put("summary", entity.getSummary());
        value.put("content", entity.getContent()); value.put("category", entity.getCategory());
        value.put("status", entity.getStatus()); value.put("scope", entity.getScopeType());
        value.put("projectId", entity.getProjectId());
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("记忆版本快照序列化失败", exception); }
    }

    private static String verdictMessage(MemoryWriteOutcome outcome, MemoryWriteJudger.Judgement judgement) {
        return switch (outcome) {
            case CREATED -> "已按用户偏好写入记忆";
            case UPDATED -> "已在原记忆上合并更新：" + bounded(judgement.reason(), 120);
            case CONFLICT_CREATED -> "与既有记忆冲突，但仍写入；采纳时按上下文择优";
            case SKIP_DUPLICATE -> "与既有记忆语义重复，跳过且不改动既有记忆";
            case SKIP_DISABLED -> "命中已停用记忆，未复活也未建立替身";
            case NEEDS_CONFIRMATION -> "发现无法自动合并的冲突：" + bounded(judgement.recommendation(), 160);
            case SKIP_NOT_WORTH -> "一次性任务指令，不具长期价值，拒写";
        };
    }

    private List<ExistingMemories> recallNear(MemoryScope scope, Long projectId, List<MemoryCandidate> candidates) {
        Map<Long, ExistingMemories> existing = new LinkedHashMap<>();
        for (MemoryCandidate candidate : candidates) {
            LambdaQueryWrapper<UserMemoryEntity> wrapper = Wrappers.lambdaQuery();
            wrapper.eq(UserMemoryEntity::getScopeType, scope.name());
            if (scope == MemoryScope.PROJECT) {
                wrapper.eq(UserMemoryEntity::getProjectId, projectId);
            }
            wrapper.and(inner -> {
                if (candidate.category() != null) {
                    inner.eq(UserMemoryEntity::getCategory, candidate.category().name());
                }
                boolean first = candidate.category() == null;
                for (String term : tokens(candidate)) {
                    String like = "%" + term + "%";
                    if (first) {
                        inner.like(UserMemoryEntity::getTitle, like).or()
                                .like(UserMemoryEntity::getSummary, like).or()
                                .like(UserMemoryEntity::getContent, like);
                        first = false;
                    } else {
                        inner.or().like(UserMemoryEntity::getTitle, like)
                                .or().like(UserMemoryEntity::getSummary, like)
                                .or().like(UserMemoryEntity::getContent, like);
                    }
                }
            });
            wrapper.orderByDesc(UserMemoryEntity::getUpdatedAt)
                    .orderByDesc(UserMemoryEntity::getId)
                    .last("limit " + properties.nearDuplicateRecallLimit());
            for (UserMemoryEntity entity : mapper.selectList(wrapper).stream().limit(3).toList()) {
                existing.putIfAbsent(entity.getId(), new ExistingMemories(entity.getId(),
                        MemoryCategory.valueOf(entity.getCategory()),
                        MemoryStatus.valueOf(entity.getStatus()),
                        entity.getTitle(), entity.getSummary(), entity.getContent(),
                        entity.getRevision() == null ? 1L : entity.getRevision()));
            }
        }
        return List.copyOf(existing.values());
    }

    private static List<String> tokens(MemoryCandidate candidate) {
        List<String> terms = MemoryRelevanceScorer.tokenize(
                (candidate.title() == null ? "" : candidate.title()) + " "
                        + (candidate.content() == null ? "" : candidate.content()));
        return terms.size() > 5 ? terms.subList(0, 5) : terms;
    }

    private MemoryCategory candidateCategory(MemoryCandidate candidate) {
        return candidate.category() == null ? MemoryCategory.OTHER : candidate.category();
    }

    private void requireCandidatesValid(List<MemoryCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "候选不能为空");
        }
        if (candidates.size() > properties.candidateLimit()) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID,
                    "单次候选不能超过 " + properties.candidateLimit() + " 条");
        }
        for (MemoryCandidate candidate : candidates) {
            requireText("候选标题", candidate.title(), 1, properties.titleMaxLength());
            requireText("候选正文", candidate.content(), 1, properties.contentMaxLength());
        }
    }

    private ProjectScope requireEnabledProject(Long projectId) {
        ProjectScope scope;
        try {
            scope = projectService.resolveScope(projectId);
        } catch (RuntimeException exception) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_PROJECT_INVALID, "项目不存在");
        }
        if (!scope.enabled()) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_PROJECT_INVALID, "项目已停用");
        }
        return scope;
    }

    // ------------------------------------------------------------------ 管理

    @Override
    public MemoryPage listPage(MemoryPageQuery query) {
        LambdaQueryWrapper<UserMemoryEntity> wrapper = Wrappers.lambdaQuery();
        if (query.scope() != null) {
            wrapper.eq(UserMemoryEntity::getScopeType, query.scope().name());
        }
        if (query.category() != null) {
            wrapper.eq(UserMemoryEntity::getCategory, query.category().name());
        }
        if (query.status() != null) {
            wrapper.eq(UserMemoryEntity::getStatus, query.status().name());
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String like = "%" + bound(query.keyword().strip(), 100) + "%";
            wrapper.and(inner -> inner.like(UserMemoryEntity::getTitle, like)
                    .or().like(UserMemoryEntity::getSummary, like)
                    .or().like(UserMemoryEntity::getContent, like));
        }
        int page = Math.max(query.page(), 1);
        int size = Math.max(1, Math.min(query.size() <= 0 ? 20 : query.size(), 100));
        long total = mapper.selectCount(wrapper);
        List<MemoryFull> items = mapper.selectList(wrapper
                        .orderByDesc(UserMemoryEntity::getUpdatedAt)
                        .orderByDesc(UserMemoryEntity::getId)
                        .last("limit " + size + " offset " + ((long) (page - 1) * size)))
                .stream().map(MemoryEntityTransforms::toFull).toList();
        return new MemoryPage(total, page, size, items);
    }

    @Override
    @Transactional
    public MemoryFull create(MemoryDraftInput command) {
        MemoryScope scope = enumOf(MemoryScope.class, command.scope() == null ? null : command.scope().name(),
                "记忆范围");
        if (scope == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "记忆范围必填");
        }
        if (scope == MemoryScope.PROJECT && command.projectId() == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "项目记忆必须绑定项目");
        }
        MemoryCategory category = enumOf(MemoryCategory.class,
                command.category() == null ? null : command.category().name(), "分类");
        if (category == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "分类必填");
        }
        requireText("标题", command.title(), 1, properties.titleMaxLength());
        requireText("正文", command.content(), 1, properties.contentMaxLength());
        String summary = command.summary() == null || command.summary().isBlank()
                ? bound(command.content(), properties.summaryMaxLength())
                : bound(command.summary(), properties.summaryMaxLength());
        ProjectScope project = scope == MemoryScope.PROJECT ? requireEnabledProject(command.projectId()) : null;
        mapper.lockWriteScope(lockKey(scope, command.projectId()));
        Instant now = clock.instant();
        UserMemoryEntity entity = UserMemoryEntity.builder()
                .revision(1L)
                .scopeType(scope.name())
                .projectId(project == null ? null : project.projectId())
                .projectIdentifier(project == null ? null : project.projectIdentifier())
                .category(category.name())
                .title(command.title())
                .summary(summary)
                .content(command.content())
                .sourceType(MemorySourceType.MANUAL.name())
                .status(MemoryStatus.ACTIVE.name())
                .useCount(0L)
                .createdBy(command.operatorId())
                .updatedBy(command.operatorId())
                .createdAt(now)
                .updatedAt(now)
                .build();
        mapper.insert(entity);
        saveRevision(entity, "CREATE", "NEW", "人工创建", null, null, null, command.operatorId(), now);
        log.info("记忆人工创建 id={} scope={} category={}", entity.getId(), scope, category);
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    @Transactional
    public MemoryFull update(MemoryEditInput command) {
        UserMemoryEntity entity = mapper.selectById(command.id());
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        requireExpectedRevision(entity, command.expectedRevision());
        mapper.lockWriteScope(lockKey(entity));
        // 范围与所属项目不可编辑（变更范围视为新建）；一旦传入即整体拒绝、不改任何字段
        if (command.scope() != null || command.projectId() != null) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_SCOPE_EDIT_FORBIDDEN, "记忆范围与所属项目不可编辑");
        }
        if (command.category() != null) {
            entity.setCategory(enumOf(MemoryCategory.class, command.category().name(), "分类").name());
        }
        if (command.title() != null) {
            requireText("标题", command.title(), 1, properties.titleMaxLength());
            entity.setTitle(command.title());
        }
        if (command.summary() != null) {
            entity.setSummary(bound(command.summary(), properties.summaryMaxLength()));
        }
        if (command.content() != null) {
            requireText("正文", command.content(), 1, properties.contentMaxLength());
            entity.setContent(command.content());
        }
        if (command.status() != null) {
            entity.setStatus(enumOf(MemoryStatus.class, command.status().name(), "状态").name());
        }
        Instant now = clock.instant();
        long nextRevision = (entity.getRevision() == null ? 1L : entity.getRevision()) + 1;
        entity.setRevision(nextRevision);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(command.operatorId());
        int affected = command.expectedRevision() == null
                ? mapper.updateById(entity) : mapper.updateMerged(entity.getId(), entity.getCategory(), entity.getTitle(),
                entity.getSummary(), entity.getContent(), nextRevision, nextRevision - 1, now, command.operatorId());
        if (affected != 1) throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_WRITE_STALE,
                "记忆版本已变化，请刷新后重试");
        saveRevision(entity, "UPDATE", "MANUAL", "管理员人工编辑", null, null, null, command.operatorId(), now);
        log.info("记忆人工编辑 id={} 分类={} 标题码点={} 状态={}",
                entity.getId(), entity.getCategory(), entity.getTitle().codePointCount(0, entity.getTitle().length()),
                entity.getStatus());
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    @Transactional
    public MemoryFull setStatus(Long memoryId, MemoryStatus status, String operatorId, Long expectedRevision) {
        UserMemoryEntity entity = mapper.selectById(memoryId);
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        requireExpectedRevision(entity, expectedRevision);
        mapper.lockWriteScope(lockKey(entity));
        MemoryStatus target = enumOf(MemoryStatus.class, status == null ? null : status.name(), "状态");
        if (target == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "状态非法");
        }
        entity.setStatus(target.name());
        Instant now = clock.instant();
        long nextRevision = (entity.getRevision() == null ? 1L : entity.getRevision()) + 1;
        entity.setRevision(nextRevision);
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(operatorId);
        int affected = expectedRevision == null ? mapper.updateById(entity)
                : mapper.updateMerged(entity.getId(), entity.getCategory(), entity.getTitle(), entity.getSummary(),
                entity.getContent(), nextRevision, nextRevision - 1, now, operatorId);
        if (affected != 1) throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_WRITE_STALE,
                "记忆版本已变化，请刷新后重试");
        saveRevision(entity, "STATUS", "MANUAL", "管理员变更状态", null, null, null, operatorId, now);
        log.info("记忆状态变更 id={} 状态={} 操作者={}", memoryId, target, operatorId);
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    @Transactional
    public void delete(Long memoryId, Long expectedRevision) {
        UserMemoryEntity entity = mapper.selectById(memoryId);
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        requireExpectedRevision(entity, expectedRevision);
        mapper.lockWriteScope(lockKey(entity));
        if (revisions != null) revisions.sanitizeBeforeDelete(memoryId);
        mapper.deleteById(memoryId);
        log.info("记忆删除 id={}", memoryId);
    }

    @Override
    public MemoryRevisionPage listRevisions(Long memoryId, int page, int size) {
        UserMemoryEntity current = mapper.selectById(memoryId);
        if (current == null || revisions == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.max(1, Math.min(size <= 0 ? 20 : size, 50));
        long total = revisions.countByMemoryId(memoryId);
        List<MemoryRevision> items = revisions.selectPageByMemoryId(memoryId, normalizedSize,
                        (long) (normalizedPage - 1) * normalizedSize).stream().map(this::toRevision).toList();
        return new MemoryRevisionPage(total, normalizedPage, normalizedSize, items);
    }

    private MemoryRevision toRevision(UserMemoryRevisionEntity entity) {
        return new MemoryRevision(entity.getId(), entity.getMemoryId(), entity.getRevision(), entity.getSnapshot(),
                entity.getOperation(), entity.getRelation(), entity.getReason(), entity.getSourceRunId(),
                entity.getSourceConversationId(), entity.getSourceMessageId(), entity.getOperatorId(),
                entity.getCreatedAt() == null ? null : entity.getCreatedAt().atOffset(java.time.ZoneOffset.UTC));
    }

    private static void requireExpectedRevision(UserMemoryEntity entity, Long expectedRevision) {
        if (expectedRevision != null && expectedRevision > 0
                && expectedRevision.longValue() != (entity.getRevision() == null ? 1L : entity.getRevision())) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_WRITE_STALE,
                    "记忆版本已变化，请刷新后重试");
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void requireText(String name, String value, int min, int max) {
        if (value == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, name + "必填");
        }
        int count = value.codePointCount(0, value.length());
        if (count < min || count > max) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID,
                    name + "长度必须在 " + min + "~" + max + " 码点之间");
        }
    }

    private static String bound(String value, int limit) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        int count = text.codePointCount(0, text.length());
        return count <= limit ? text : text.substring(0, text.offsetByCodePoints(0, limit));
    }

    private static String bounded(String value, int limit) {
        String text = bound(value, limit);
        return text == null ? "" : text;
    }

    private static <T extends Enum<T>> T enumOf(Class<T> type, String value, String name) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_FIELD_INVALID, name + "非法：" + value);
        }
    }
}
