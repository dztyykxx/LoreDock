package io.github.loredock.project.infrastructure.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "loredock.demo.seed-enabled=false"
)
@ActiveProfiles("test")
@Testcontainers
class DemoProjectDataPreparerIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_demo_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private DemoProjectDataPreparer preparer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    void clearProjects() {
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
    }

    /**
     * 业务目的：即使处于 test profile，显式开关关闭时启动也不得自动准备数据，防止测试或生产被隐式样例污染。
     */
    @Test
    void disabledSeedFlagDoesNotPrepareProjectsOnStartup() {
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_space", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_branch", Integer.class)).isZero();
    }

    /**
     * 业务目的：首次显式准备需创建两个安全演示项目、各自 main，以及网络设计项目的场景包演示分支。
     */
    @Test
    void firstPreparationCreatesTwoProjectsAndDemoBranches() {
        DemoPreparationReport report = preparer.prepare();

        assertThat(report).isEqualTo(new DemoPreparationReport(2, 0, 3, 0));
        assertThat(jdbcTemplate.queryForList(
                "select identifier from project_space order by identifier", String.class))
                .containsExactly("lightweight-comparison", "network-designer");
        assertThat(jdbcTemplate.queryForList("""
                select p.identifier || ':' || b.name
                from project_branch b join project_space p on p.id = b.project_id
                order by p.identifier, b.name
                """, String.class))
                .containsExactly(
                        "lightweight-comparison:main",
                        "network-designer:feature/import-export",
                        "network-designer:main");
    }

    /**
     * 业务目的：重复准备必须报告全部复用且不新增记录，避免验收脚本重跑制造重复项目或分支。
     */
    @Test
    void repeatedPreparationReusesAllProjectsAndBranches() {
        preparer.prepare();

        DemoPreparationReport repeated = preparer.prepare();

        assertThat(repeated).isEqualTo(new DemoPreparationReport(0, 2, 0, 3));
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_space", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_branch", Integer.class)).isEqualTo(3);
    }

    /**
     * 业务目的：准备器只能写入公开的中性演示元数据，不得包含公司名称、内部地址、Token、密码或真实 Wiki 正文。
     */
    @Test
    void preparedMetadataContainsNoCredentialOrInternalBusinessMaterial() {
        preparer.prepare();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select name, description, technology_stack from project_space order by identifier");
        assertThat(rows).allSatisfy(row -> {
            String combined = row.values().toString().toLowerCase();
            assertThat(combined)
                    .doesNotContain("password", "token", "authorization", "jdbc:", "wiki", "company", "corp");
        });
    }
}
