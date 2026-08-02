package io.github.loredock.knowledge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class McpProtocolIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("loredock_mcp_protocol_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("loredock.mcp.read-token", () -> "read-secret");
        registry.add("loredock.mcp.write-token", () -> "write-secret");
        registry.add("loredock.identity.web.accounts[0].username", () -> "admin");
        registry.add("loredock.identity.web.accounts[0].display-name", () -> "管理员");
        registry.add("loredock.identity.web.accounts[0].role", () -> "ADMIN");
        registry.add("loredock.identity.web.accounts[0].password-hash", () -> BCRYPT_HASH);
        registry.add("loredock.identity.web.accounts[1].username", () -> "member");
        registry.add("loredock.identity.web.accounts[1].display-name", () -> "成员");
        registry.add("loredock.identity.web.accounts[1].role", () -> "MEMBER");
        registry.add("loredock.identity.web.accounts[1].password-hash", () -> BCRYPT_HASH);
    }

    /**
     * 业务目的：真实 Streamable HTTP 客户端必须能完成握手并且只发现批准的六个工具；
     * 防止仅 Java 注解测试通过，但协议路由或框架自动注册在运行时失效或越权。
     */
    @Test
    void initializesStreamableHttpAndListsOnlyApprovedTools() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> initialize = client.send(request("read-secret", null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"loredock-test","version":"1"}}}
                """), HttpResponse.BodyHandlers.ofString());

        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.body()).contains("serverInfo", "loredock");
        String sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();
        HttpResponse<String> initialized = client.send(request("read-secret", sessionId, """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """), HttpResponse.BodyHandlers.ofString());
        assertThat(initialized.statusCode()).isIn(200, 202);
        HttpResponse<String> tools = client.send(request("read-secret", sessionId, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """), HttpResponse.BodyHandlers.ofString());

        assertThat(tools.statusCode()).isEqualTo(200);
        assertThat(tools.body()).contains(
                "knowledge_search", "knowledge_directory_list", "knowledge_document_list",
                "knowledge_document_read", "knowledge_grep", "knowledge_draft_submit");
        assertThat(tools.body()).doesNotContain("draft_publish", "shell", "code_search");

        seedProject();
        String callBody = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"knowledge_draft_submit","arguments":{"project":"atlas","title":"退款审批规则","markdown":"# 退款审批\\n\\n超过阈值需人工审核。","directory":"待处理","tags":["退款"],"originalFilename":"refund.md"}}}
                """;
        HttpResponse<String> readTokenWrite = client.send(request("read-secret", sessionId, callBody),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> writeTokenWrite = client.send(request("write-secret", sessionId, callBody),
                HttpResponse.BodyHandlers.ofString());

        assertThat(readTokenWrite.body()).contains("isError", "true", "MCP_WRITE_TOKEN_REQUIRED");
        assertThat(writeTokenWrite.body()).contains("documentId", "DRAFT");
        assertThat(jdbc.queryForObject(
                "select count(*) from knowledge_document where project_id = 9001 and status = 'DRAFT'",
                Integer.class)).isEqualTo(1);
        System.out.println("MCP 协议测试证据：transport=Streamable HTTP，session=已建立，registeredTools=6，"
                + "readTokenSubmit=已拒绝，writeTokenDrafts=1");
    }

    /**
     * 业务目的：MCP 协议路由必须在解析 initialize 内容前拒绝匿名请求；
     * 防止客户端能从服务信息或工具列表侧信道发现内部能力。
     */
    @Test
    void rejectsAnonymousProtocolInitialization() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"anonymous","version":"1"}}}
                        """))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEmpty();
        System.out.println("MCP 协议鉴权测试证据：anonymousInitializeStatus=" + response.statusCode());
    }

    private HttpRequest request(String token, String sessionId, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
        }
        return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    private void seedProject() {
        jdbc.update("""
                insert into project_space(
                    id, identifier, name, description, technology_stack, status,
                    created_at, updated_at, created_by, updated_by)
                values (9001, 'atlas', 'Atlas', '', 'Java', 'ENABLED', now(), now(), 'test', 'test')
                on conflict (id) do nothing
                """);
        jdbc.update("""
                insert into project_branch(project_id, name, created_at, updated_at, created_by, updated_by)
                select 9001, 'main', now(), now(), 'test', 'test'
                where not exists(select 1 from project_branch where project_id = 9001 and name = 'main')
                """);
    }
}
