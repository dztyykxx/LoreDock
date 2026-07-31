package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillCatalog;
import io.github.loredock.agent.application.AgentSkillContentStore;
import io.github.loredock.agent.application.AgentSkillVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class BuiltinProjectQaSkillPublisherIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final Path STORAGE_ROOT = temporaryStorageRoot();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_agent_skill_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired private BuiltinProjectQaSkillPublisher publisher;
    @Autowired private AgentSkillCatalog catalog;
    @Autowired private AgentSkillVersionRepository versions;
    @Autowired private AgentSkillContentStore contentStore;
    @Autowired private ProjectQaSkillValidator validator;
    @Autowired private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.storage.root", () -> STORAGE_ROOT.toString());
        registry.add("loredock.agent.enabled", () -> "true");
        registry.add("loredock.identity.mcp.token-sha256", () -> "a".repeat(64));
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
    void republishBuiltinSkill() {
        jdbcTemplate.update("delete from agent_skill_version");
        jdbcTemplate.update("delete from stored_object");
        publisher.publishBuiltin();
    }

    /**
     * 业务目的：重复启动必须按内容哈希复用同一 Skill 版本和对象，不得产生重复发布事实。
     */
    @Test
    void repeatedBootstrapReusesContentHashAndMetadata() {
        var first = publisher.publishBuiltin();
        var second = publisher.publishBuiltin();
        var enabled = catalog.findEnabled("project_qa").orElseThrow();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.objectKey()).isEqualTo(first.objectKey());
        assertThat(jdbcTemplate.queryForObject("select count(*) from agent_skill_version", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from stored_object", Integer.class)).isEqualTo(1);
        assertThat(enabled.contentHash()).isEqualTo(first.contentHash());
        assertThat(enabled.markdown()).contains("项目业务问答");
        System.out.printf("测试证据：场景=Skill幂等引导，名称=%s，版本=%s，元数据数=1，对象数=1%n",
                enabled.name(), enabled.version());
    }

    /**
     * 业务目的：同语义版本不得悄然替换内容；新版发布后旧运行仍能按固定 object key 读取旧内容。
     */
    @Test
    void conflictingSameVersionFailsWhileRetiredVersionRemainsReadable() throws Exception {
        ProjectQaSkillDefinition builtin = builtinDefinition();
        jdbcTemplate.update("delete from agent_skill_version");
        jdbcTemplate.update("delete from stored_object");
        String oldMarkdown = builtin.markdown().replaceFirst("version: 1\\.0\\.1", "version: 1.0.0");
        publisher.publish(validator.validate(oldMarkdown, builtin.outputSchema()));
        var old = versions.findByNameAndVersion("project_qa", "1.0.0").orElseThrow();
        byte[] oldContent = contentStore.get(old.objectKey());

        publisher.publishBuiltin();
        ProjectQaSkillDefinition conflict = new ProjectQaSkillDefinition(
                builtin.name(), builtin.version(), builtin.outputSchemaVersion(), builtin.maxSteps(),
                builtin.markdown() + "\n同版本非法改动\n", builtin.outputSchema());

        assertThatThrownBy(() -> publisher.publish(conflict))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同版本内容冲突");

        assertThat(catalog.findEnabled("project_qa").orElseThrow().version()).isEqualTo("1.0.1");
        assertThat(versions.findByNameAndVersion("project_qa", "1.0.0").orElseThrow().status())
                .isEqualTo("RETIRED");
        assertThat(contentStore.get(old.objectKey())).isEqualTo(oldContent);
        System.out.println("测试证据：场景=Skill版本固定，同版本冲突=拒绝，启用版本=1.0.1，旧版本1.0.0=可读");
    }

    /**
     * 业务目的：即使 ObjectStorage 内容仍存在，数据库已退役的 Skill 也不得被新运行选中。
     */
    @Test
    void databaseEnabledStatusIsCatalogSourceOfTruth() {
        String objectKey = versions.findEnabled("project_qa").orElseThrow().objectKey();
        jdbcTemplate.update("update agent_skill_version set status = 'RETIRED' where skill_name = 'project_qa'");

        assertThat(contentStore.get(objectKey)).isNotEmpty();
        assertThat(catalog.findEnabled("project_qa")).isEmpty();
        System.out.println("测试证据：场景=Skill事实来源，对象存在=true，数据库ENABLED=false，目录可用=false");
    }

    private ProjectQaSkillDefinition builtinDefinition() throws IOException {
        return validator.validate(resource("agent-skills/project_qa/SKILL.md"),
                resource("agent-skills/project_qa/output-schema.json"));
    }

    private String resource(String path) throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("resource missing: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path temporaryStorageRoot() {
        try {
            return Files.createTempDirectory("loredock-agent-skill-");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
