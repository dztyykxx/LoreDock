package io.github.loredock.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.agent.model.command.AgentRunCreateData;
import io.github.loredock.agent.model.result.AgentRunRetrieval;
import io.github.loredock.agent.service.AgentRetrievalService;
import io.github.loredock.agent.service.AgentRunService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AgentRetrievalPersistenceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Long PROJECT_ID = 2891640495451214098L;
    private static final Long BRANCH_ID = 916404954512140971L;
    private static final Long DOCUMENT_ID = 6241483468158498680L;
    private static final Long SNAPSHOT_ID = 2133123963609712777L;
    private static final Long RUN_ID = 8000000000000000101L;

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_retrieval_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private AgentRunService runs;
    @Autowired private AgentRetrievalService retrievals;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock timeProvider;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
    }

    @BeforeEach
    void resetRuntimeFacts() {
        jdbcTemplate.update("delete from agent_run_retrieval");
        jdbcTemplate.update("delete from agent_evidence");
        jdbcTemplate.update("delete from agent_run_event");
        jdbcTemplate.update("delete from agent_run");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from code_snapshot");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        jdbcTemplate.update("delete from stored_object");
        seedScopeAndSkill();
        runs.insert(createData(RUN_ID));
    }

    /**
     * 业务目的：检索记录按调用顺序持久化，读取时能还原查询词、每个候选的相关度、保留状态、
     * 模型实际看到的片段以及裁剪标记，支撑忠实度与 Top-5 指标。
     */
    @Test
    void appendThenFindByRunIdReturnsRecordsInOrderWithModelSnippet() {
        int first = retrievals.append(RUN_ID, "为什么审核", List.of(
                new AgentRunRetrieval.RetrievedDocument(
                        8000000000000000011L, DOCUMENT_ID, "人工审核与正式发布规则",
                        0.91, true, "候选内容在发布前不进入普通检索", false)));
        int second = retrievals.append(RUN_ID, "重试几次", List.of(
                new AgentRunRetrieval.RetrievedDocument(
                        8000000000000000012L, DOCUMENT_ID + 1, "导入失败与重试策略",
                        0.72, false, null, true)));

        List<AgentRunRetrieval> recorded = retrievals.findByRunId(RUN_ID);

        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(recorded).hasSize(2);
        assertThat(recorded.get(0).sequenceNo()).isEqualTo(1);
        assertThat(recorded.get(0).query()).isEqualTo("为什么审核");
        assertThat(recorded.get(0).documents()).singleElement().satisfies(doc -> {
            assertThat(doc.documentId()).isEqualTo(DOCUMENT_ID);
            assertThat(doc.title()).isEqualTo("人工审核与正式发布规则");
            assertThat(doc.relevance()).isEqualTo(0.91);
            assertThat(doc.retained()).isTrue();
            assertThat(doc.content()).isEqualTo("候选内容在发布前不进入普通检索");
            assertThat(doc.truncated()).isFalse();
        });
        assertThat(recorded.get(1).sequenceNo()).isEqualTo(2);
        assertThat(recorded.get(1).query()).isEqualTo("重试几次");
        assertThat(recorded.get(1).documents()).singleElement().satisfies(doc -> {
            assertThat(doc.retained()).isFalse();
            assertThat(doc.content()).isNull();
            assertThat(doc.truncated()).isTrue();
        });
        System.out.printf("测试证据：场景=检索记录持久化，runId=%s，检索次数=%d，首条查询=%s，保留片段=%s%n",
                RUN_ID, recorded.size(), recorded.get(0).query(),
                recorded.get(0).documents().getFirst().content());
    }

    /**
     * 业务目的：空结果也必须保留一次检索记录，评估程序据此区分"检索过但没有证据"与"未检索"。
     */
    @Test
    void appendEmptyRetrievalIsPersisted() {
        int sequence = retrievals.append(RUN_ID, "没有答案的问题", List.of());

        List<AgentRunRetrieval> recorded = retrievals.findByRunId(RUN_ID);

        assertThat(sequence).isEqualTo(1);
        assertThat(recorded).singleElement().satisfies(value -> {
            assertThat(value.query()).isEqualTo("没有答案的问题");
            assertThat(value.documents()).isEmpty();
        });
        System.out.printf("测试证据：场景=空检索记录，runId=%s，查询=%s，命中数=0%n",
                RUN_ID, recorded.getFirst().query());
    }

    /**
     * 业务目的：检索记录必须归属真实运行，避免脏数据在评估统计中混淆不同的运行结果。
     */
    @Test
    void appendRejectsRunWithoutExistingRunRow() {
        assertThatThrownBy(() -> retrievals.append(8000000000000000999L, "越权运行", List.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
        System.out.println("测试证据：场景=检索记录外键约束，不存在的 runId 被拒绝");
    }

    private AgentRunCreateData createData(Long runId) {
        return new AgentRunCreateData(runId, "member", "retrieval-key", "c".repeat(64), "project_qa", "d".repeat(64), 12,
                new io.github.loredock.agent.model.snapshot.AgentScopeSnapshot(
                        PROJECT_ID, "atlas", BRANCH_ID, "main", null, null, null,
                        List.of("GLOBAL", "PROJECT", "BRANCH")),
                new io.github.loredock.agent.model.snapshot.AgentVersionSnapshot(
                        "project_qa", "deepseek-v4-flash", "project-qa-v1"),
                timeProvider.instant());
    }

    private void seedScopeAndSkill() {
        jdbcTemplate.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('skill-one', 'AVAILABLE', 'skill.md', 'text/markdown', 10, ?, now(), now(), 'test', 'test')
                """, "a".repeat(64));
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'atlas', 'Atlas', '', '', 'ENABLED', now(), now(), 'test', 'test')
                """, PROJECT_ID);
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, 'main', now(), now(), 'test', 'test')
                """, BRANCH_ID, PROJECT_ID);
        jdbcTemplate.update("""
                insert into stored_object(object_key, status, original_filename, content_type, size_bytes,
                    sha256, created_at, updated_at, created_by, updated_by)
                values ('public-code-fixture', 'AVAILABLE', 'public-code.zip', 'application/zip', 10,
                    ?, now(), now(), 'test', 'test')
                """, "b".repeat(64));
        jdbcTemplate.update("""
                insert into code_snapshot(id, project_id, branch_id, commit_hash, input_object_key, status,
                    indexed_file_count, ignored_file_count, indexed_at,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, 'abcdef1', 'public-code-fixture', 'ACTIVE', 3, 0, now(),
                    now(), now(), 'test', 'test')
                """, SNAPSHOT_ID, PROJECT_ID, BRANCH_ID);
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type, project_id,
                    branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'MARKDOWN', '业务规则', '公开模拟正文', '/', 'PROJECT', ?,
                    null, 'MANUAL', 'PUBLISHED', 1, now(), 'test', now(), now(), 'test', 'test')
                """, DOCUMENT_ID, PROJECT_ID);
    }
}
