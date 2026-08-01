package io.github.loredock.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.auth.TestAuthFactory;
import io.github.loredock.auth.service.AccountService;
import io.github.loredock.knowledge.converter.KnowledgeSearchHttpContract;
import io.github.loredock.knowledge.exception.KnowledgeEmbeddingUnavailableException;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingModelDescriptor;
import io.github.loredock.knowledge.model.result.KnowledgeEmbeddingVector;
import io.github.loredock.knowledge.service.search.KnowledgeEmbeddingService;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
@Import(KnowledgeSearchWebE2EIT.TestIdentityConfiguration.class)
class KnowledgeSearchWebE2EIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Instant NOW = Instant.parse("2026-07-30T00:00:00Z");
    private static final Long GENERATION_ID = 3591110776164450306L;
    private static final Long PROJECT_A = 3591110776164450307L;
    private static final Long PROJECT_B = 3591110776164450308L;
    private static final Long A_MAIN = 3591110776164450309L;
    private static final Long A_OTHER = 3591110776164450310L;
    private static final Long B_MAIN = 3591110776164450311L;
    private static final Long GLOBAL_DOCUMENT = 6494917836444794882L;
    private static final Long PROJECT_DOCUMENT = 6494917836444794883L;
    private static final Long BRANCH_DOCUMENT = 6494917836444794884L;
    private static final Long OTHER_BRANCH_DOCUMENT = 6494917836444794885L;
    private static final Long OTHER_PROJECT_DOCUMENT = 6494917836444794886L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_search_web_e2e_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KnowledgeEmbeddingService embedding;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "成员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
    }

    @BeforeEach
    void resetDatabaseAndSessions() {
        SaTokenDaoDefaultImpl sessions = new SaTokenDaoDefaultImpl();
        sessions.init();
        SaManager.setSaTokenDao(sessions);
        reset(embedding);
        when(embedding.describeModel()).thenReturn(new KnowledgeEmbeddingModelDescriptor(
                "BAAI/bge-small-zh-v1.5", "b".repeat(64), 512));
        when(embedding.embedQuery(any())).thenReturn(new KnowledgeEmbeddingVector(axisVector(0)));

        jdbcTemplate.update("delete from knowledge_search_chunk");
        jdbcTemplate.update("delete from knowledge_index_generation");
        jdbcTemplate.update("delete from knowledge_document");
        jdbcTemplate.update("delete from background_job");
        jdbcTemplate.update("delete from code_index_generation");
        jdbcTemplate.update("delete from code_snapshot");
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
        seedProject(PROJECT_A, "project-a");
        seedProject(PROJECT_B, "project-b");
        seedBranch(A_MAIN, PROJECT_A, "main");
        seedBranch(A_OTHER, PROJECT_A, "feature/other");
        seedBranch(B_MAIN, PROJECT_B, "main");
        seedGeneration();
        seedDocument(GLOBAL_DOCUMENT, "恢复总则", "统一恢复流程", "GLOBAL", null, null, axisVector(0));
        seedDocument(PROJECT_DOCUMENT, "项目发布", "项目恢复发布", "PROJECT", PROJECT_A, null,
                mixedVector(0.8f, 0.6f));
        seedDocument(BRANCH_DOCUMENT, "场景恢复", "main 分支场景包恢复", "BRANCH", PROJECT_A, A_MAIN,
                mixedVector(0.9f, 0.4f));
        seedDocument(OTHER_BRANCH_DOCUMENT, "场景恢复", "其他分支更近内容", "BRANCH", PROJECT_A, A_OTHER,
                axisVector(0));
        seedDocument(OTHER_PROJECT_DOCUMENT, "场景恢复", "其他项目更近内容", "BRANCH", PROJECT_B, B_MAIN,
                axisVector(0));
    }

    /**
     * 业务目的：ADMIN 与 MEMBER 必须通过完整 Web→应用→PostgreSQL 链路执行三种模式并得到有限引用，不能泄漏其他项目或分支。
     */
    @Test
    void adminAndMemberUseAllModesWithCitationsAndZeroScopeLeakage() throws Exception {
        Cookie member = loginCookie("member");
        Cookie admin = loginCookie("admin");
        List<String> modes = List.of("KEYWORD", "SEMANTIC", "HYBRID");
        for (int index = 0; index < modes.size(); index++) {
            Cookie actor = index == 0 ? admin : member;
            String mode = modes.get(index);
            long started = System.nanoTime();
            MvcResult result = search(actor, mode, 10).andExpect(status().isOk())
                    .andExpect(jsonPath("$.context.type").value("PROJECT"))
                    .andExpect(jsonPath("$.context.branch").value("main"))
                    .andExpect(jsonPath("$.generationId").value(GENERATION_ID.toString()))
                    .andExpect(jsonPath("$.warnings").isEmpty())
                    .andExpect(jsonPath("$.results[0].documentId").exists())
                    .andExpect(jsonPath("$.results[0].snippet").isString())
                    .andExpect(jsonPath("$.results[0].source.type").value("MANUAL"))
                    .andExpect(jsonPath("$.results[0].relevance").isNumber())
                    .andReturn();
            JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
            List<String> ids = resultIds(body);
            assertThat(ids).doesNotContain(OTHER_BRANCH_DOCUMENT.toString(), OTHER_PROJECT_DOCUMENT.toString());
            assertThat(body.get("results").size()).isLessThanOrEqualTo(10);
            System.out.printf("测试证据：场景=知识搜索Web端到端，角色=%s，mode=%s，范围=project-a/main，"
                            + "generation=%s，结果ID=%s，泄漏数=0，耗时毫秒=%d%n",
                    index == 0 ? "ADMIN" : "MEMBER", mode, GENERATION_ID, ids,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    /**
     * 业务目的：匿名、非法参数、无搜索 generation 与模型不可用必须由完整 HTTP 链路区分为 401、400 和两类 503。
     */
    @Test
    void webFailuresRemainAuthenticatedValidatedAndSemanticallyDistinct() throws Exception {
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", "恢复").queryParam("context", "GLOBAL"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_LOGIN_REQUIRED"));
        Cookie member = loginCookie("member");
        mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                        .queryParam("query", " ").queryParam("context", "GLOBAL").cookie(member))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // V5 起搜索元数据与 generation 合并为单表，撤下 ACTIVE 模拟“无可用索引”。
        jdbcTemplate.update("update knowledge_index_generation set status = 'RETIRED' where id = ?", GENERATION_ID);
        search(member, "KEYWORD", 10).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_INDEX_UNAVAILABLE"));
        jdbcTemplate.update("update knowledge_index_generation set status = 'ACTIVE' where id = ?", GENERATION_ID);
        doThrow(new KnowledgeEmbeddingUnavailableException()).when(embedding).describeModel();
        search(member, "SEMANTIC", 10).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KNOWLEDGE_EMBEDDING_UNAVAILABLE"));
        System.out.println("测试证据：场景=知识搜索Web失败语义，匿名=401，非法参数=400，无索引=503/KNOWLEDGE_INDEX_UNAVAILABLE，"
                + "模型不可用=503/KNOWLEDGE_EMBEDDING_UNAVAILABLE");
    }

    /**
     * 业务目的：同一 generation 和输入必须稳定排序；候选在搜索前已归档时应实时删除且不从候选范围外补足。
     */
    @Test
    void repeatedHybridOrderingIsStableAndArchivedTopCandidateIsNotBackfilled() throws Exception {
        Cookie member = loginCookie("member");
        JsonNode first = JSON.readTree(search(member, "HYBRID", 10).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode second = JSON.readTree(search(member, "HYBRID", 10).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(resultIds(second)).containsExactlyElementsOf(resultIds(first));

        String topId = resultIds(first).getFirst();
        jdbcTemplate.update("""
                update knowledge_document
                set status='ARCHIVED', archived_at=?, archived_by='archiver', updated_at=?
                where id=?
                """, Timestamp.from(NOW.plusSeconds(1)), Timestamp.from(NOW.plusSeconds(1)), Long.valueOf(topId));
        JsonNode afterArchive = JSON.readTree(search(member, "HYBRID", 1).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(resultIds(afterArchive)).doesNotContain(topId);
        assertThat(afterArchive.get("results").size()).isZero();
        System.out.printf("测试证据：场景=稳定排序与实时归档，初始顺序=%s，重复顺序=%s，归档Top1=%s，"
                        + "limit=1返回数=0，补足=false%n",
                resultIds(first), resultIds(second), topId);
    }

    private org.springframework.test.web.servlet.ResultActions search(Cookie cookie, String mode, int limit)
            throws Exception {
        return mockMvc.perform(get(KnowledgeSearchHttpContract.BASE_PATH)
                .queryParam("query", "恢复")
                .queryParam("context", "PROJECT")
                .queryParam("project", "project-a")
                .queryParam("branch", "main")
                .queryParam("mode", mode)
                .queryParam("limit", Integer.toString(limit))
                .cookie(cookie));
    }

    private Cookie loginCookie(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("loredock_session");
    }

    private List<String> resultIds(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("results").forEach(result -> ids.add(result.get("documentId").asText()));
        return List.copyOf(ids);
    }

    private void seedGeneration() {
        Long jobId = 2398724896725139458L;
        jdbcTemplate.update("""
                insert into background_job(id, job_type, status, progress, created_at, updated_at,
                    created_by, updated_by)
                values (?, 'KNOWLEDGE_REINDEX', 'SUCCEEDED', 100, ?, ?, 'test', 'test')
                """, jobId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbcTemplate.update("""
                insert into knowledge_index_generation(id, job_id, status, model_id, model_checksum,
                    vector_dimension, chunk_strategy_version, fusion_config_version,
                    document_count, chunk_count, created_at, activated_at)
                values (?, ?, 'ACTIVE', 'BAAI/bge-small-zh-v1.5', ?, 512, 'cjk-v1', 'rrf-v1', 5, 5, ?, ?)
                """, GENERATION_ID, jobId, "b".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedProject(Long id, String identifier) {
        jdbcTemplate.update("""
                insert into project_space(id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, '测试项目', 'Java', 'ENABLED', ?, ?, 'test', 'test')
                """, id, identifier, identifier, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedBranch(Long id, Long projectId, String name) {
        jdbcTemplate.update("""
                insert into project_branch(id, project_id, name, created_at, updated_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'test', 'test')
                """, id, projectId, name, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private void seedDocument(
            Long id,
            String title,
            String content,
            String scopeType,
            Long projectId,
            Long branchId,
            float[] vector
    ) {
        jdbcTemplate.update("""
                insert into knowledge_document(id, format, title, body, directory_path, scope_type,
                    project_id, branch_id, source_type, status, revision, published_at, published_by,
                    created_at, updated_at, created_by, updated_by)
                values (?, 'MARKDOWN', ?, ?, '', ?, ?, ?, 'MANUAL', 'PUBLISHED', 1, ?, 'publisher',
                    ?, ?, 'test', 'test')
                """, id, title, content, scopeType, projectId, branchId,
                Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        // V5 起索引投影不再保留独立 knowledge_index_document 表，分块已携带标题、正文与范围字段。
        jdbcTemplate.update("""
                insert into knowledge_search_chunk(generation_id, document_id, chunk_no, start_offset, end_offset,
                    content, source_revision, title, tags, title_terms, tag_terms, content_terms, search_vector,
                    embedding, scope_type, project_id, branch_id, format, source_type, normalized_tags,
                    source_updated_at)
                values (?, ?, 0, 0, ?, ?, 1, ?, '["恢复"]'::jsonb, ?, '恢复', ?,
                    setweight(to_tsvector('simple', ?), 'A') || setweight(to_tsvector('simple', '恢复'), 'B') ||
                    setweight(to_tsvector('simple', ?), 'C'), cast(? as vector), ?, ?, ?, 'MARKDOWN', 'MANUAL',
                    ?, ?)
                """, GENERATION_ID, id, content.codePointCount(0, content.length()), content, title,
                title.replace("", " ").strip(), content.replace("", " ").strip(),
                title.replace("", " ").strip(), content.replace("", " ").strip(), vectorLiteral(vector),
                scopeType, projectId, branchId, new String[]{"恢复"}, Timestamp.from(NOW));
    }

    private float[] axisVector(int axis) {
        float[] vector = new float[512];
        vector[axis] = 1;
        return vector;
    }

    private float[] mixedVector(float first, float second) {
        float[] vector = new float[512];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(vector[index]);
        }
        return literal.append(']').toString();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestIdentityConfiguration {

        @Bean
        @Primary
        AccountService testLoginUseCase() {
            return TestAuthFactory.accountService();
        }
    }
}
