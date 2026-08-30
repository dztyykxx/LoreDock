package io.github.loredock.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.memory.api.MemoryCandidate;
import io.github.loredock.memory.api.MemoryCategory;
import io.github.loredock.memory.api.MemoryDraftInput;
import io.github.loredock.memory.api.MemoryFull;
import io.github.loredock.memory.api.MemoryRelevant;
import io.github.loredock.memory.api.MemoryRelevantQuery;
import io.github.loredock.memory.api.MemoryRequestException;
import io.github.loredock.memory.api.MemoryScope;
import io.github.loredock.memory.api.MemoryService;
import io.github.loredock.memory.api.MemoryStatus;
import io.github.loredock.memory.api.MemoryWriteOutcome;
import io.github.loredock.memory.api.MemoryWriteInput;
import io.github.loredock.memory.api.MemoryWriteVerdict;
import io.github.loredock.memory.config.MemoryProperties;
import io.github.loredock.memory.mapper.UserMemoryMapper;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
import io.github.loredock.memory.testsupport.MemoryTestFixtures;
import io.github.loredock.persistence.MybatisMapperFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 记忆检索/摘要预载（行为 A）、全文按需加载（行为 B）与写入判断链（行为 C）
 * 真实 PostgreSQL 集成测试。数据只通过 Mapper 直接落库，业务只经 {@link MemoryService} 契约调用；
 * 打分器输出与排序（含判断链）在每个场景内固定（测试时钟 + 脚本化判断模型）。
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryServiceIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_memory_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    private static final Instant BASE = Instant.parse("2026-08-30T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), ZoneOffset.UTC);
    /** 来源外键种子：知识整理 run/会话（memory_write 来源引用）。 */
    private static final long RUN_ID = 999L;
    private static final long CONV_ID = 8888L;
    private static final long BRANCH_ID = 901L;
    private static final String HEX64 = "a".repeat(64);

    private DataSource dataSource;
    private UserMemoryMapper mapper;
    private MemoryService service;
    /** 项目 A/B 主键，超范围隔离用。 */
    private static final long PROJECT_A = 101L;
    private static final long PROJECT_B = 202L;

    @BeforeAll
    void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // 项目外键：PROJECT 范围记忆必须指向真实存在的项目空间
        for (long projectId : new long[]{PROJECT_A, PROJECT_B}) {
            new JdbcTemplate(dataSource).update("""
                    insert into project_space
                        (id, identifier, name, description, technology_stack, status,
                         created_at, updated_at, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 'ENABLED', ?, ?, 'test', 'test')
                    """, projectId, "project-" + projectId, "项目" + projectId,
                    "测试项目", "java", BASE.atOffset(ZoneOffset.UTC), BASE.atOffset(ZoneOffset.UTC));
        }
        // 来源外键链：project_branch → agent_run → user_memory 的 source_run / knowledge_task_conversation
        new JdbcTemplate(dataSource).update("""
                insert into project_branch (id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', ?, ?, 'test', 'test')
                """, BRANCH_ID, PROJECT_A, BASE.atOffset(ZoneOffset.UTC), BASE.atOffset(ZoneOffset.UTC));
        new JdbcTemplate(dataSource).update("""
                insert into knowledge_task_conversation
                    (id, operator_id, idempotency_key, request_hash, project_id, project_identifier,
                     trigger_type, trigger_reason, target_skill, goal, created_at, updated_at)
                values (?, 'test', 'mem-conv-8888', ?, ?, ?, 'MANUAL', '测试记忆来源跑批', 'memory', '记忆集成测试目标', ?, ?)
                """, CONV_ID, HEX64, PROJECT_A, "project-" + PROJECT_A,
                BASE.atOffset(ZoneOffset.UTC), BASE.atOffset(ZoneOffset.UTC));
        new JdbcTemplate(dataSource).update("""
                insert into agent_run
                    (id, operator_id, idempotency_key, request_hash, task_type, question_hash,
                     question_length, project_id, project_identifier, branch_id, branch_name,
                     agent_name, model_name, config_summary, status, accepted_at, updated_at)
                values (?, 'test', 'mem-run-999', ?, 'knowledge_curation', ?, 20, ?, ?, ?, 'main',
                        'xc', 'test', 'it-config', 'RUNNING', ?, ?)
                """, RUN_ID, HEX64, HEX64, PROJECT_A, "project-" + PROJECT_A, BRANCH_ID,
                BASE.atOffset(ZoneOffset.UTC), BASE.atOffset(ZoneOffset.UTC));
        mapper = MybatisMapperFactory.create(dataSource, UserMemoryMapper.class);
        // 手动插入显式 id 不会推进 identity 序列；写路径用自增 id，必须跳过手动段（否则撞主键）
        new JdbcTemplate(dataSource).queryForObject(
                "select setval(pg_get_serial_sequence('user_memory', 'id'), 1000)", Long.class);
        service = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                MemoryTestFixtures.judgerNeverCalled(),
                MemoryTestFixtures.properties(), CLOCK);
    }

    /**
     * 业务目的：① 检索范围内只能看到 GLOBAL ∪ 项目 A，项目 B 记忆不得泄漏，
     * 防止跨项目偏好互相污染后生成错误指令（范围隔离）。
     */
    @Test
    @Order(1)
    void searchDoesNotLeakOtherProjectMemories() {
        insert(1L, "GLOBAL", null, "通用格式偏好", "正文包含：文档一律用三级标题");
        insert(2L, "PROJECT", PROJECT_A, "项目A格式偏好", "正文包含：文档简称用 atlas");
        insert(3L, "PROJECT", PROJECT_B, "项目B格式偏好", "正文包含：文档称谓用 apollo");

        List<MemoryRelevant> hits = service.listRelevant(
                new MemoryRelevantQuery(List.of("文档"), PROJECT_A, 0));

        assertThat(hits.stream().map(MemoryRelevant::id)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(hits.stream().map(MemoryRelevant::id)).doesNotContain(3L);
        System.out.println("测试证据：场景=跨项目检索隔离，GLOBAL∪项目A=2 条，项目B 记忆=不可见");
    }

    /**
     * 业务目的：② 同查询词下标题命中必须排在正文命中之前（确定性打分），
     * 防止命中强度被正文长度或插入顺序反超，保证摘要预载的稳定优先级。
     */
    @Test
    @Order(2)
    void titleHitOutranksContentHit() {
        // 标题命中（score=3，摘要与正文不含查询词）必须排在正文命中（score=1）之前
        insert(11L, "GLOBAL", null, "文档指南：段落编排",
                "正文：对季度总结，先结论后展开", "正文：对季度总结，先结论后展开");
        insert(12L, "GLOBAL", null, "季度总结偏好",
                "摘要：季度总结成文规范", "正文：季度总结必须先写文档概览再展开");

        List<MemoryRelevant> hits = service.listRelevant(
                new MemoryRelevantQuery(List.of("文档"), null, 0));

        assertThat(hits.stream().map(MemoryRelevant::id)).startsWith(11L);
        System.out.println("测试证据：场景=打分排序，标题命中文档=id11 位于正文命中文档=id12 之前");
    }

    /**
     * 业务目的：③ 命中超过 30 条时摘要预载只返回前 30 条、每条摘要不超长，
     * 防止预载块无限膨胀顶破主 Agent 上下文预算（有界返回）。
     */
    @Test
    @Order(3)
    void preloadIsBoundedToThirtyAndSummariesAreShorterThanLimit() {
        for (int index = 0; index < 35; index++) {
            insert(100L + index, "GLOBAL", null, "标题" + index,
                    "正文采用简体中文并包含关键词文档编号" + index + "：" + "摘要正文".repeat(40));
        }

        List<MemoryRelevant> hits = service.listRelevant(
                new MemoryRelevantQuery(List.of("文档"), null, 0));

        assertThat(hits).hasSize(30);
        assertThat(hits).allMatch(hit -> hit.summary().codePointCount(0, hit.summary().length()) <= 300);
        System.out.println("测试证据：场景=预载上限，命中35 条返回=30 条，摘要最长码点=只读取库内≤300 摘要");
    }

    /**
     * 业务目的：④ 查询无命中时按最近使用的高频记忆兜底且不超过 3 条，
     * 保证新会话没有命中也能看到最常被采纳的偏好，但兜底不突破范围隔离（项目 B 不进兜底）。
     */
    @Test
    @Order(4)
    void noMatchFallsBackToRecentlyUsedHotMemoriesWithinScope() {
        // 完全不含查询词“排版术语”的记忆
        insert(200L, "GLOBAL", null, "高频1", "正文：通用用语偏好");
        insert(201L, "GLOBAL", null, "高频2", "正文：通用用语偏好");
        insert(202L, "GLOBAL", null, "高频3", "正文：通用用语偏好");
        insert(203L, "PROJECT", PROJECT_A, "项目A高频", "正文：项目A用语偏好");
        insert(204L, "PROJECT", PROJECT_B, "项目B高频", "正文：项目B用语偏好");
        // 高频记忆：created_at 提前 60 秒（满足 last_used_at >= created_at 检查），再手工置频次
        mapper.updateById(UserMemoryEntity.builder().id(200L).createdAt(BASE.minusSeconds(60))
                .useCount(12L).lastUsedAt(BASE.plusSeconds(1)).build());
        mapper.updateById(UserMemoryEntity.builder().id(201L).createdAt(BASE.minusSeconds(60))
                .useCount(7L).lastUsedAt(BASE.minusSeconds(1)).build());
        mapper.updateById(UserMemoryEntity.builder().id(202L).createdAt(BASE.minusSeconds(60))
                .useCount(4L).lastUsedAt(BASE.minusSeconds(2)).build());
        mapper.updateById(UserMemoryEntity.builder().id(203L).createdAt(BASE.minusSeconds(60))
                .useCount(4L).lastUsedAt(BASE.minusSeconds(3)).build());

        List<MemoryRelevant> hits = service.listRelevant(
                new MemoryRelevantQuery(List.of("排版术语"), PROJECT_A, 0));

        assertThat(hits).hasSize(3);
        assertThat(hits.stream().map(MemoryRelevant::id)).containsExactly(200L, 201L, 202L);
        System.out.println("测试证据：场景=无命中兜底，返回高频 Top3=id200/201/202，项目B 高频=未进入兜底");
    }

    /**
     * 业务目的：⑤ 项目 B 的记忆对项目 A 不可达，加载必须拒答且不计使用频次，
     * 防止越权读到其他项目偏好并虚增热度（超范围加载拒答）。
     */
    @Test
    @Order(5)
    void loadFullDeniedForForeignProjectMemoryKeepsUseCount() {
        insert(300L, "PROJECT", PROJECT_B, "B记记忆", "正文：B 项目机密偏好");
        UserMemoryEntity before = mapper.selectById(300L);
        assertThat(before.getUseCount()).isZero();

        assertThatThrownBy(() -> service.loadFull(300L, PROJECT_A))
                .isInstanceOf(MemoryRequestException.class)
                .extracting(exception -> ((MemoryRequestException) exception).code())
                .isEqualTo(MemoryRequestException.Code.MEMORY_SCOPE_VIOLATION);

        UserMemoryEntity after = mapper.selectById(300L);
        assertThat(after.getUseCount()).isZero();
        assertThat(after.getLastUsedAt()).isNull();
        System.out.println("测试证据：场景=越权加载，项目A 读项目B 编号=300 → 拒绝，use_count=0，last_used_at=null");
    }

    /**
     * 业务目的：⑥ 合法加载返回全文并递增使用频次、刷新最近使用时间，
     * 使后续检索排序权重提升，实现“越常用越靠前”。
     */
    @Test
    @Order(6)
    void loadFullWithinScopeReturnsContentAndTouchesUseCount() {
        insert(400L, "GLOBAL", null, "全局偏好", "正文：Markdown 表格宽度不超过 40 列");
        UserMemoryEntity before = mapper.selectById(400L);
        assertThat(before.getUseCount()).isZero();

        MemoryFull full = service.loadFull(400L, null);

        assertThat(full.content()).contains("40 列");
        UserMemoryEntity after = mapper.selectById(400L);
        assertThat(after.getUseCount()).isEqualTo(1L);
        assertThat(after.getLastUsedAt()).isEqualTo(CLOCK.instant());
        System.out.println("测试证据：场景=合法加载，正文返回，use_count=1，last_used_at=测试时钟 10:00:00Z");
    }

    // ------------------------------------------------------------------ 写入判断链

    /**
     * 业务目的：⑦ 与既有记忆语义重复时跳过且不改动既有记忆，
     * 防止同一偏好被反复沉淀成噪音，也防止把旧记忆的标题/频次无意覆盖。
     */
    @Test
    @Order(7)
    void duplicateMemoryIsSkippedAndExistingUntouched() {
        seed(500L, "GLOBAL", null, MemoryCategory.FORMAT, "文档正文格式偏好",
                "正文用三级标题结构", "文档正文一律用三级标题结构组织");
        MemoryWriteJudger scripted = new MemoryWriteJudger(MemoryTestFixtures.single(new ScriptedChatModel("""
                [{"candidateIndex":0,"verdict":"SKIP_DUPLICATE","conflictsWith":[],"summary":null}]
                """)), new ObjectMapper());
        MemoryService writeService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                scripted, MemoryTestFixtures.properties(), CLOCK);

        List<MemoryWriteVerdict> verdicts = writeService.acceptWrite(request(
                null, List.of(new MemoryCandidate("文档正文格式偏好", "正文用三级标题结构", MemoryCategory.FORMAT, null))));

        assertThat(verdicts).extracting(MemoryWriteVerdict::outcome)
                .containsExactly(MemoryWriteOutcome.SKIP_DUPLICATE);
        assertThat(verdicts.get(0).memoryId()).isNull();
        UserMemoryEntity existing = mapper.selectById(500L);
        assertThat(existing.getStatus()).isEqualTo(MemoryStatus.ACTIVE.name());
        assertThat(existing.getUseCount()).isZero();
        assertThat(existing.getSummary()).isEqualTo("正文用三级标题结构");
        System.out.println("测试证据：场景=语义重复，id=500 未被改动，use_count=0，判断=SKIP_DUPLICATE");
    }

    /**
     * 业务目的：⑧ 停用记忆即使被召回也不得复活，重复判断必须对 DISABLED 记忆照常去重，
     * 防止人工停用被自动写入绕过（停用记忆进入判断上下文，但结论仍为跳过）。
     */
    @Test
    @Order(8)
    void disabledMemoryIsNeverRevived() {
        seed(501L, "GLOBAL", null, MemoryCategory.FORMAT, "正文格式习惯",
                "正文用三级标题结构", "文档正文用三级标题结构组织");
        mapper.updateById(UserMemoryEntity.builder().id(501L)
                .status(MemoryStatus.DISABLED.name()).updatedAt(CLOCK.instant()).build());
        ScriptedChatModel scriptedModel = new ScriptedChatModel("""
                [{"candidateIndex":0,"verdict":"SKIP_DUPLICATE","conflictsWith":[],"summary":null}]
                """);
        MemoryService writeService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                new MemoryWriteJudger(MemoryTestFixtures.single(scriptedModel), new ObjectMapper()),
                MemoryTestFixtures.properties(), CLOCK);

        writeService.acceptWrite(request(null,
                List.of(new MemoryCandidate("正文格式习惯", "文档正文用三级标题结构组织", MemoryCategory.FORMAT, null))));

        assertThat(mapper.selectById(501L).getStatus()).isEqualTo(MemoryStatus.DISABLED.name());
        assertThat(scriptedModel.lastPrompt()).contains("#501 [DISABLED FORMAT]");
        assertThat(mapper.selectCount(Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getTitle, "正文格式习惯"))).isEqualTo(1L);
        System.out.println("测试证据：场景=停用不复活，召回含 #501 [DISABLED]，结论=SKIP_DUPLICATE，记录仍 DISABLED");
    }

    /**
     * 业务目的：⑨ 与既有记忆语义冲突时仍写入（冲突双写、双方保持 ACTIVE），
     * 防止“后写的偏好吞掉先写的”，由模型在采纳时刻按当前任务上下文择优。
     */
    @Test
    @Order(9)
    void conflictingMemoryIsWrittenAnyway() {
        seed(502L, "GLOBAL", null, MemoryCategory.STYLE, "中文写作风格",
                "面向用户资料，正文用口语风格", "面向用户资料正文用口语风格");
        MemoryWriteJudger scripted = new MemoryWriteJudger(MemoryTestFixtures.single(new ScriptedChatModel("""
                [{"candidateIndex":0,"verdict":"CONFLICT_CREATED","conflictsWith":[502],"summary":"面向用户资料用书面风格"}]
                """)), new ObjectMapper());
        MemoryService writeService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                scripted, MemoryTestFixtures.properties(), CLOCK);

        List<MemoryWriteVerdict> verdicts = writeService.acceptWrite(request(
                null, List.of(new MemoryCandidate("中文写作风格", "面向用户资料，正文必须书面风格", MemoryCategory.STYLE, null))));

        assertThat(verdicts.get(0).outcome()).isEqualTo(MemoryWriteOutcome.CONFLICT_CREATED);
        assertThat(verdicts.get(0).conflictsWith()).containsExactly(502L);
        UserMemoryEntity written = mapper.selectById(verdicts.get(0).memoryId());
        assertThat(written.getStatus()).isEqualTo(MemoryStatus.ACTIVE.name());
        assertThat(written.getScopeType()).isEqualTo("GLOBAL");
        assertThat(written.getCategory()).isEqualTo(MemoryCategory.STYLE.name());
        assertThat(written.getSourceType()).isEqualTo("KNOWLEDGE_CURATION");
        assertThat(written.getSourceRunId()).isEqualTo(RUN_ID);
        assertThat(written.getSummary()).isEqualTo("面向用户资料用书面风格");
        assertThat(mapper.selectById(502L).getStatus()).isEqualTo(MemoryStatus.ACTIVE.name());
        System.out.println("测试证据：场景=冲突双写，新记忆=" + written.getId() + "，与 id=502 均 ACTIVE，conflictsWith=[502]");
    }

    /**
     * 业务目的：⑩ 一次性任务指令不具长期价值，判断为拒写，
     * 防止把“本次只改标题”之类的临时指令沉淀为今后永远生效的记忆。
     */
    @Test
    @Order(10)
    void oneTimeInstructionIsNotWorthWriting() {
        MemoryWriteJudger scripted = new MemoryWriteJudger(MemoryTestFixtures.single(new ScriptedChatModel("""
                [{"candidateIndex":0,"verdict":"SKIP_NOT_WORTH","conflictsWith":[],"summary":null}]
                """)), new ObjectMapper());
        MemoryService writeService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                scripted, MemoryTestFixtures.properties(), CLOCK);

        List<MemoryWriteVerdict> verdicts = writeService.acceptWrite(request(
                null, List.of(new MemoryCandidate("本次只改标题", "这次任务只先改标题，正文不处理", MemoryCategory.OTHER, null))));

        assertThat(verdicts.get(0).outcome()).isEqualTo(MemoryWriteOutcome.SKIP_NOT_WORTH);
        assertThat(verdicts.get(0).memoryId()).isNull();
        assertThat(mapper.selectCount(Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getTitle, "本次只改标题"))).isZero();
        System.out.println("测试证据：场景=一次性指令，未写入任何记录，判断=SKIP_NOT_WORTH");
    }

    /**
     * 业务目的：⑪ 单 run 累计新增达到预算后整体拒写，
     * 防止一次会话源源不断产生记忆、绕过人工管理（预算归集口径=source_run_id）。
     */
    @Test
    @Order(11)
    void budgetPerRunRejectsWhenExceeded() {
        // 预算=2：先落两条同 run 历史新写记录，第 3 次即使候选合法也必须拒写
        seed(600L, "GLOBAL", null, MemoryCategory.FORMAT, "预算记忆1", "正文预备用", "正文预备用");
        seed(601L, "GLOBAL", null, MemoryCategory.FORMAT, "预算记忆2", "正文预备用", "正文预备用");
        mapper.updateById(UserMemoryEntity.builder().id(600L).sourceType("KNOWLEDGE_CURATION")
                .sourceRunId(RUN_ID).sourceConversationId(CONV_ID).build());
        mapper.updateById(UserMemoryEntity.builder().id(601L).sourceType("KNOWLEDGE_CURATION")
                .sourceRunId(RUN_ID).sourceConversationId(CONV_ID).build());
        MemoryService writeService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.projectService(PROJECT_A, PROJECT_B),
                MemoryTestFixtures.judgerNeverCalled(), MemoryTestFixtures.properties(2), CLOCK);

        assertThatThrownBy(() -> writeService.acceptWrite(request(null,
                List.of(new MemoryCandidate("预算记忆3", "正文预备用", MemoryCategory.FORMAT, null)))))
                .isInstanceOf(MemoryRequestException.class)
                .extracting(exception -> ((MemoryRequestException) exception).code())
                .isEqualTo(MemoryRequestException.Code.MEMORY_BUDGET_EXCEEDED);
        assertThat(mapper.selectCount(Wrappers.<UserMemoryEntity>lambdaQuery()
                .eq(UserMemoryEntity::getTitle, "预算记忆3"))).isZero();
        System.out.println("测试证据：场景=写入预算，run=999 已新写=2 条，达到预算=2，第 3 条拒写");
    }

    /**
     * 业务目的：⑬ PROJECT 记忆必须绑定存在且启用的项目——人工创建指向不存在或已停用
     * 项目时整体拒绝且不产生记录，防止把偏好绑进失效项目变成永久孤儿数据。
     */
    @Test
    @Order(12)
    void createRequiresExistingEnabledProject() {
        MemoryService adminService = new MemoryServiceImpl(
                mapper, MemoryTestFixtures.disabledProjectService(PROJECT_B),
                MemoryTestFixtures.judgerNeverCalled(), MemoryTestFixtures.properties(), CLOCK);

        // 项目不存在：resolveScope 越界 → MEMORY_PROJECT_INVALID
        assertThatThrownBy(() -> service.create(new MemoryDraftInput(
                MemoryScope.PROJECT, 999L, MemoryCategory.FORMAT, "孤立项目记忆", "摘要", "正文", "test")))
                .isInstanceOf(MemoryRequestException.class)
                .extracting(exception -> ((MemoryRequestException) exception).code())
                .isEqualTo(MemoryRequestException.Code.MEMORY_PROJECT_INVALID);
        // 项目存在但已停用：enabled=false → MEMORY_PROJECT_INVALID
        assertThatThrownBy(() -> adminService.create(new MemoryDraftInput(
                MemoryScope.PROJECT, PROJECT_B, MemoryCategory.FORMAT, "停用项目记忆", "摘要", "正文", "test")))
                .isInstanceOf(MemoryRequestException.class)
                .extracting(exception -> ((MemoryRequestException) exception).code())
                .isEqualTo(MemoryRequestException.Code.MEMORY_PROJECT_INVALID);
        assertThat(mapper.selectCount(Wrappers.<UserMemoryEntity>lambdaQuery()
                .in(UserMemoryEntity::getTitle, "孤立项目记忆", "停用项目记忆"))).isZero();
        System.out.println("测试证据：场景=人工创建，不存在/已停用项目均拒写且零记录");
    }

    // ------------------------------------------------------------------ 数据

    private MemoryWriteInput request(Long projectId, List<MemoryCandidate> candidates) {
        return new MemoryWriteInput(projectId, RUN_ID, CONV_ID, "test", candidates);
    }

    private long insert(Long id, String scope, Long projectId, String title, String content) {
        // 摘要默认取正文前 300 码点（与生产缺省行为一致）
        return insert(id, scope, projectId, title,
                content.length() > 300 ? content.substring(0, 300) : content, content);
    }

    private long insert(Long id, String scope, Long projectId, String title, String summary, String content) {
        return seed(id, scope, projectId, MemoryCategory.CONTENT, title, summary, content);
    }

    private long seed(Long id, String scope, Long projectId, MemoryCategory category,
            String title, String summary, String content) {
        UserMemoryEntity entity = UserMemoryEntity.builder()
                .id(id)
                .scopeType(scope)
                .projectId(projectId)
                .projectIdentifier(projectId == null ? null : "project-" + projectId)
                .category(category.name())
                .title(title)
                .summary(summary)
                .content(content)
                .sourceType("MANUAL")
                .status(MemoryStatus.ACTIVE.name())
                .useCount(0L)
                .createdAt(BASE)
                .updatedAt(BASE)
                .createdBy("test")
                .updatedBy("test")
                .build();
        mapper.insert(entity);
        return id;
    }

    private static ChatResponse answer(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** 脚本化判断模型：任何 prompt 一律返回预置 JSON 数组，并记录最近一次 prompt 供断言。 */
    private static final class ScriptedChatModel implements ChatModel {

        private final String reply;
        private String lastPrompt;

        private ScriptedChatModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt.getInstructions().get(0).getText();
            return answer(reply);
        }

        private String lastPrompt() {
            return lastPrompt;
        }
    }
}
