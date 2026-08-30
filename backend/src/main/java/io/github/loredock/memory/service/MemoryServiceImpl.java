package io.github.loredock.memory.service;

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
import io.github.loredock.memory.api.MemoryWriteVerdict;
import io.github.loredock.memory.config.MemoryProperties;
import io.github.loredock.memory.mapper.UserMemoryMapper;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
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

/**
 * 记忆业务实现：检索有界化与范围隔离（SQL 层闭合）、全文按需加载计入频次、
 * 提炼写入判断链（值得写/重复/冲突仍写 + run 预算）、人工管理路径校验。
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

    public MemoryServiceImpl(
            UserMemoryMapper mapper,
            ProjectService projectService,
            MemoryWriteJudger judger,
            MemoryProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.projectService = projectService;
        this.judger = judger;
        this.properties = properties;
        this.clock = clock;
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
    public List<MemoryWriteVerdict> acceptWrite(MemoryWriteInput request) {
        requireCandidatesValid(request.candidates());
        if (request.sourceRunId() == null || request.sourceConversationId() == null
                || request.operatorId() == null || request.operatorId().isBlank()) {
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_FIELD_INVALID, "来源 run、会话与操作者必填");
        }
        long written = mapper.countBySourceRun(request.sourceRunId());
        if (written >= properties.writeBudgetPerRun()) {
            log.warn("记忆写入预算已达上限 run={} 已新写={} 上限={}",
                    request.sourceRunId(), written, properties.writeBudgetPerRun());
            throw new MemoryRequestException(
                    MemoryRequestException.Code.MEMORY_BUDGET_EXCEEDED,
                    "本 run 新写记忆已达上限，需人工管理后继续");
        }
        MemoryScope scope = request.projectId() == null ? MemoryScope.GLOBAL : MemoryScope.PROJECT;
        ProjectScope project = scope == MemoryScope.PROJECT ? requireEnabledProject(request.projectId()) : null;

        List<ExistingMemories> existing = recallNear(scope, request.projectId(), request.candidates());
        List<MemoryWriteJudger.CandidateForJudgement> judgeInput = new ArrayList<>();
        for (MemoryCandidate candidate : request.candidates()) {
            judgeInput.add(new MemoryWriteJudger.CandidateForJudgement(
                    judgeInput.size(), candidateCategory(candidate), candidate.title(), candidate.content()));
        }
        List<MemoryWriteJudger.Judgement> judgements;
        try {
            judgements = judger.judge(judgeInput, existing);
        } catch (IllegalStateException exception) {
            log.warn("记忆写入判断模型不可用 run={} candidates={} 原因={}",
                    request.sourceRunId(), request.candidates().size(), bounded(exception.getMessage(), 200));
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_JUDGE_UNAVAILABLE,
                    "记忆写入判断不可用，请稍后重试");
        }

        Instant now = clock.instant();
        List<MemoryWriteVerdict> verdicts = new ArrayList<>();
        for (MemoryWriteJudger.Judgement judgement : judgements) {
            MemoryCandidate candidate = request.candidates().get(judgement.slot());
            switch (judgement.outcome()) {
                case SKIP_DUPLICATE, SKIP_NOT_WORTH -> verdicts.add(new MemoryWriteVerdict(judgement.slot(),
                        judgement.outcome(), null, verdictMessage(judgement), new long[0]));
                case CREATED, CONFLICT_CREATED -> {
                    Long id = persistMemory(scope, project, candidate, judgement, request, now);
                    verdicts.add(new MemoryWriteVerdict(judgement.slot(), judgement.outcome(),
                            id, verdictMessage(judgement),
                            judgement.conflictsWith().stream().mapToLong(Long::longValue).toArray()));
                }
            }
        }
        return List.copyOf(verdicts);
    }

    private Long persistMemory(MemoryScope scope, ProjectScope project, MemoryCandidate candidate,
            MemoryWriteJudger.Judgement judgement, MemoryWriteInput request, Instant now) {
        String summary = judgement.summary() != null
                ? bound(judgement.summary(), properties.summaryMaxLength())
                : bound(candidate.content(), properties.summaryMaxLength());
        UserMemoryEntity entity = UserMemoryEntity.builder()
                .scopeType(scope.name())
                .projectId(project == null ? null : project.projectId())
                .projectIdentifier(project == null ? null : project.projectIdentifier())
                .category(candidateCategory(candidate).name())
                .title(candidate.title())
                .summary(summary)
                .content(candidate.content())
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
        log.info("记忆写入 run={} scope={} outcome={} id={} 摘要码点={}",
                request.sourceRunId(), scope, judgement.outcome(), entity.getId(),
                summary.codePointCount(0, summary.length()));
        return entity.getId();
    }

    private static String verdictMessage(MemoryWriteJudger.Judgement judgement) {
        return switch (judgement.outcome()) {
            case CREATED -> "已按用户偏好写入记忆";
            case CONFLICT_CREATED -> "与既有记忆冲突，但仍写入；采纳时按上下文择优";
            case SKIP_DUPLICATE -> "与既有记忆语义重复，跳过且不改动既有记忆";
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
            for (UserMemoryEntity entity : mapper.selectList(wrapper)) {
                existing.putIfAbsent(entity.getId(), new ExistingMemories(entity.getId(),
                        MemoryCategory.valueOf(entity.getCategory()),
                        MemoryStatus.valueOf(entity.getStatus()),
                        entity.getTitle(), entity.getSummary()));
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
        Instant now = clock.instant();
        UserMemoryEntity entity = UserMemoryEntity.builder()
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
        log.info("记忆人工创建 id={} scope={} category={}", entity.getId(), scope, category);
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    public MemoryFull update(MemoryEditInput command) {
        UserMemoryEntity entity = mapper.selectById(command.id());
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
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
        entity.setUpdatedAt(clock.instant());
        entity.setUpdatedBy(command.operatorId());
        mapper.updateById(entity);
        log.info("记忆人工编辑 id={} 分类={} 标题码点={} 状态={}",
                entity.getId(), entity.getCategory(), entity.getTitle().codePointCount(0, entity.getTitle().length()),
                entity.getStatus());
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    public MemoryFull setStatus(Long memoryId, MemoryStatus status, String operatorId) {
        UserMemoryEntity entity = mapper.selectById(memoryId);
        if (entity == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        MemoryStatus target = enumOf(MemoryStatus.class, status == null ? null : status.name(), "状态");
        if (target == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_FIELD_INVALID, "状态非法");
        }
        entity.setStatus(target.name());
        entity.setUpdatedAt(clock.instant());
        entity.setUpdatedBy(operatorId);
        mapper.updateById(entity);
        log.info("记忆状态变更 id={} 状态={} 操作者={}", memoryId, target, operatorId);
        return MemoryEntityTransforms.toFull(mapper.selectById(entity.getId()));
    }

    @Override
    public void delete(Long memoryId) {
        if (mapper.selectById(memoryId) == null) {
            throw new MemoryRequestException(MemoryRequestException.Code.MEMORY_NOT_FOUND, "记忆不存在");
        }
        mapper.deleteById(memoryId);
        log.info("记忆删除 id={}", memoryId);
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
