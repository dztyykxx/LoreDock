package io.github.loredock.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** 验证 Spring AI Alibaba PostgresSaver 只使用 Flyway 提供的兼容表。 */
@Testcontainers
class PostgresSaverCompatibilityIT {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_saver_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    /**
     * 业务目的：CREATE_NONE 在空 schema 中不得偷偷建表，保证数据库结构始终只由 Flyway 管理。
     */
    @Test
    void createNoneDoesNotCreateMissingTables() throws Exception {
        execute("create schema saver_none");

        PostgresSaver saver = PostgresSaver.builder()
                .datasource(dataSource("saver_none"))
                .createOption(CreateOption.CREATE_NONE)
                .build();

        assertThat(saver).isNotNull();
        assertThat(tableExists("saver_none", "graphthread")).isFalse();
        assertThat(tableExists("saver_none", "graphcheckpoint")).isFalse();
        System.out.println("测试证据：场景=PostgresSaver CREATE_NONE，运行时新建表数=0");
    }

    /**
     * 业务目的：Flyway 建立的 identity 主键加 UUID 协议键结构必须能被锁定版本 PostgresSaver 直接接入。
     */
    @Test
    void createNoneUsesFlywayManagedCompatibleTables() throws Exception {
        String schema = "saver_ready";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        PostgresSaver saver = PostgresSaver.builder()
                .datasource(dataSource(schema))
                .createOption(CreateOption.CREATE_NONE)
                .build();

        assertThat(saver).isNotNull();
        assertThat(tableExists(schema, "graphthread")).isTrue();
        assertThat(tableExists(schema, "graphcheckpoint")).isTrue();
        System.out.println("测试证据：场景=Flyway Graph表兼容，PostgresSaver模式=CREATE_NONE，协议表数=2");
    }

    /**
     * 业务目的：可恢复长任务重启后必须从数据库读取最后已提交节点和副作用标记，避免重复执行已完成写入。
     */
    @Test
    void restartedSaverLoadsCommittedCheckpointWithoutRepeatingSideEffect() throws Exception {
        String schema = "saver_resume";
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DataSource projectDataSource = dataSource(schema);
        RunnableConfig thread = RunnableConfig.builder().threadId("knowledge-mining-42").build();
        PostgresSaver firstProcess = saver(projectDataSource);
        firstProcess.put(thread, Checkpoint.builder()
                .id("d2f62f10-4f19-4ad7-b12a-268df9bf8f01")
                .nodeId("persist_knowledge")
                .nextNodeId("mine_relations")
                .state(Map.of("committedSideEffects", List.of("knowledge:42")))
                .build());

        PostgresSaver restartedProcess = saver(projectDataSource);
        Checkpoint recovered = restartedProcess.get(thread).orElseThrow();
        int repeatedWrites = recovered.getState().containsKey("committedSideEffects") ? 0 : 1;

        assertThat(recovered.getNextNodeId()).isEqualTo("mine_relations");
        assertThat(recovered.getState().get("committedSideEffects")).isEqualTo(List.of("knowledge:42"));
        assertThat(repeatedWrites).isZero();
        assertThat(rowCount(schema, "graphthread")).isEqualTo(1);
        assertThat(rowCount(schema, "graphcheckpoint")).isEqualTo(1);
        System.out.println("测试证据：场景=Checkpoint重启恢复，恢复节点=mine_relations，已提交副作用重复数=0，checkpoint=1");
    }

    private PostgresSaver saver(DataSource dataSource) throws Exception {
        return PostgresSaver.builder()
                .datasource(dataSource)
                .createOption(CreateOption.CREATE_NONE)
                .build();
    }

    private DataSource dataSource(String schema) {
        String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean tableExists(String schema, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     select exists(
                         select 1 from information_schema.tables
                         where table_schema = ? and table_name = ?
                     )
                     """)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private int rowCount(String schema, String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from " + schema + "." + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
