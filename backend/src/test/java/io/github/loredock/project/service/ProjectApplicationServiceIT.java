package io.github.loredock.project.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.project.exception.BranchNameConflictException;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.exception.ProjectIdentifierConflictException;
import io.github.loredock.project.exception.ProjectNotFoundException;
import io.github.loredock.project.model.command.AddBranchCommand;
import io.github.loredock.project.model.command.ChangeProjectStatusCommand;
import io.github.loredock.project.model.command.CreateProjectCommand;
import io.github.loredock.project.model.enums.ProjectStatus;
import io.github.loredock.project.model.result.AdminProjectDetailView;
import io.github.loredock.project.model.result.AdminProjectSummaryView;
import io.github.loredock.project.model.result.BranchView;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class ProjectApplicationServiceIT {

    private static final String BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.1-pg17")
                    .asCompatibleSubstituteFor("postgres")
    ).withDatabaseName("loredock_project_test")
            .withUsername("loredock")
            .withPassword("loredock_test");

    @Autowired
    private ProjectApplicationService commands;

    @Autowired
    private ProjectApplicationService queries;

    @Autowired
    private ProjectApplicationService adminQueries;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void clearProjects() {
        jdbcTemplate.update("delete from project_branch");
        jdbcTemplate.update("delete from project_space");
    }

    /**
     * 业务目的：项目与默认 main 必须同一事务创建并带 UTC/SYSTEM 审计，防止查询到半成品范围。
     */
    @Test
    void createProjectAtomicallyPersistsEnabledProjectAndMainBranch() {
        AdminProjectDetailView created = create("network-tool", "Network Tool");

        assertThat(created.status()).isEqualTo(ProjectStatus.ENABLED);
        assertThat(created.defaultBranch()).isEqualTo("main");
        assertThat(created.branches()).extracting(BranchView::name).containsExactly("main");
        assertThat(created.createdAt()).isNotNull();
        assertThat(created.createdBy()).isEqualTo("SYSTEM");
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_space", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from project_branch", Integer.class)).isEqualTo(1);
    }

    /**
     * 业务目的：默认分支持久化失败必须回滚已插入项目，防止普通或管理入口发现孤立项目。
     */
    @Test
    void defaultBranchFailureRollsBackProjectInsert() {
        jdbcTemplate.execute("""
                create function reject_main_branch() returns trigger language plpgsql as $$
                begin
                    if new.name = 'main' then raise exception 'simulated branch failure'; end if;
                    return new;
                end $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_main_branch before insert on project_branch
                for each row execute function reject_main_branch()
                """);
        try {
            assertThatThrownBy(() -> create("rollback-project", "Rollback"))
                    .isNotInstanceOf(ProjectIdentifierConflictException.class);
            assertThat(jdbcTemplate.queryForObject("select count(*) from project_space", Integer.class)).isZero();
        } finally {
            jdbcTemplate.execute("drop trigger reject_main_branch on project_branch");
            jdbcTemplate.execute("drop function reject_main_branch()");
        }
    }

    /**
     * 业务目的：全局唯一约束必须把重复与并发创建稳定映射为项目冲突，数据库最终只保留一个完整范围。
     */
    @Test
    void duplicateAndConcurrentIdentifierCreatesLeaveOneCompleteProject() throws Exception {
        create("duplicate-project", "First");
        assertThatThrownBy(() -> create("duplicate-project", "Second"))
                .isInstanceOf(ProjectIdentifierConflictException.class);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createAfter(start, "concurrent-project", "Concurrent A"));
            var second = executor.submit(() -> createAfter(start, "concurrent-project", "Concurrent B"));
            start.countDown();
            List<Object> results = List.of(result(first), result(second));

            assertThat(results.stream().filter(AdminProjectDetailView.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ProjectIdentifierConflictException.class::isInstance)).hasSize(1);
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from project_space where identifier = 'concurrent-project'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from project_branch b join project_space p on p.id = b.project_id
                where p.identifier = 'concurrent-project' and b.name = 'main'
                """, Integer.class)).isEqualTo(1);
    }

    /**
     * 业务目的：分支名只在项目内唯一，并发重名最多一次成功，不得跨项目污染或重复创建。
     */
    @Test
    void branchCreationIsUniqueWithinProjectAndReusableAcrossProjects() throws Exception {
        Long firstProject = create("first-project", "First").id();
        Long secondProject = create("second-project", "Second").id();
        commands.addBranch(firstProject, new AddBranchCommand("feature/import-export"));
        commands.addBranch(secondProject, new AddBranchCommand("feature/import-export"));
        assertThatThrownBy(() -> commands.addBranch(firstProject, new AddBranchCommand("feature/import-export")))
                .isInstanceOf(BranchNameConflictException.class);

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> addAfter(start, firstProject, "release/v1"));
            var second = executor.submit(() -> addAfter(start, firstProject, "release/v1"));
            start.countDown();
            List<Object> results = List.of(result(first), result(second));
            assertThat(results.stream().filter(BranchView.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(BranchNameConflictException.class::isInstance)).hasSize(1);
        }
    }

    /**
     * 业务目的：停用操作幂等且不删除分支；普通查询必须在数据库范围排除停用项目，管理查询仍能恢复全部状态。
     */
    @Test
    void statusChangesAreIdempotentAndQueryScopesRemainSeparated() {
        AdminProjectDetailView created = create("status-project", "Status");
        commands.addBranch(created.id(), new AddBranchCommand("feature/keep"));

        AdminProjectDetailView disabled = commands.changeStatus(
                created.id(), new ChangeProjectStatusCommand(ProjectStatus.DISABLED));
        AdminProjectDetailView disabledAgain = commands.changeStatus(
                created.id(), new ChangeProjectStatusCommand(ProjectStatus.DISABLED));

        assertThat(disabledAgain.updatedAt()).isEqualTo(disabled.updatedAt());
        assertThat(queries.listEnabledProjects()).isEmpty();
        assertThatThrownBy(() -> queries.getEnabledProject("status-project", null))
                .isInstanceOf(ProjectNotFoundException.class);
        assertThat(adminQueries.getProject(created.id()).branches())
                .extracting(BranchView::name).containsExactlyInAnyOrder("main", "feature/keep");
        assertThat(adminQueries.listProjects(null)).extracting(AdminProjectSummaryView::status)
                .containsExactly(ProjectStatus.DISABLED);
    }

    /**
     * 业务目的：持久化往返后仍按项目解析默认或显式分支，未知分支必须失败且不能回退 main。
     */
    @Test
    void persistedProjectRoundTripsAndUnknownBranchNeverFallsBack() {
        AdminProjectDetailView created = create("roundtrip-project", "Roundtrip");
        commands.addBranch(created.id(), new AddBranchCommand("Feature/Case"));

        assertThat(queries.getEnabledProject("roundtrip-project", null).selectedBranch()).isEqualTo("main");
        assertThat(queries.getEnabledProject("roundtrip-project", "Feature/Case").selectedBranch())
                .isEqualTo("Feature/Case");
        assertThatThrownBy(() -> queries.getEnabledProject("roundtrip-project", "missing"))
                .isInstanceOf(BranchNotFoundException.class);
        assertThat(adminQueries.getProject(created.id()).identifier()).isEqualTo("roundtrip-project");
    }

    /**
     * 业务目的：跨模块契约必须把空分支稳定解析为 main，也必须保留显式分支的项目内 Long 标识。
     */
    @Test
    void projectContractResolvesDefaultAndExplicitBranch() {
        AdminProjectDetailView created = create("contract-project", "Contract");
        BranchView release = commands.addBranch(created.id(), new AddBranchCommand("release"));

        ProjectScope defaultScope = projectService.resolveEnabledScope("contract-project", null);
        ProjectScope releaseScope = projectService.resolveEnabledScope("contract-project", "release");

        assertThat(defaultScope.projectId()).isEqualTo(created.id());
        assertThat(defaultScope.branchName()).isEqualTo("main");
        assertThat(defaultScope.branchId()).isNotNull();
        assertThat(releaseScope.branchId()).isEqualTo(release.id());
        assertThat(releaseScope.branchName()).isEqualTo("release");
        System.out.println("测试证据：场景=ProjectService范围解析，项目=" + defaultScope.projectIdentifier()
                + "，默认分支=" + defaultScope.branchName() + "，显式分支=" + releaseScope.branchName());
    }

    /**
     * 业务目的：停用项目不得经普通跨模块契约进入检索和问答范围，防止绕过项目启停规则。
     */
    @Test
    void projectContractRejectsDisabledProject() {
        AdminProjectDetailView created = create("disabled-contract", "Disabled Contract");
        commands.changeStatus(created.id(), new ChangeProjectStatusCommand(ProjectStatus.DISABLED));

        assertThatThrownBy(() -> projectService.resolveEnabledScope("disabled-contract", null))
                .isInstanceOf(ProjectNotFoundException.class);
        System.out.println("测试证据：场景=ProjectService停用项目，项目=disabled-contract，结果=PROJECT_NOT_FOUND");
    }

    /**
     * 业务目的：未知分支必须返回稳定错误而不是回退 main，防止知识、代码和问答越过用户指定范围。
     */
    @Test
    void projectContractRejectsUnknownBranchWithoutFallback() {
        create("unknown-branch-contract", "Unknown Branch Contract");

        assertThatThrownBy(() -> projectService.resolveEnabledScope("unknown-branch-contract", "missing"))
                .isInstanceOf(BranchNotFoundException.class);
        System.out.println("测试证据：场景=ProjectService未知分支，项目=unknown-branch-contract，结果=BRANCH_NOT_FOUND");
    }

    private AdminProjectDetailView create(String identifier, String name) {
        return commands.createProject(new CreateProjectCommand(name, identifier, "Description", "Java 21"));
    }

    private Object createAfter(CountDownLatch start, String identifier, String name) {
        await(start);
        try {
            return create(identifier, name);
        } catch (ProjectIdentifierConflictException exception) {
            return exception;
        }
    }

    private Object addAfter(CountDownLatch start, Long projectId, String name) {
        await(start);
        try {
            return commands.addBranch(projectId, new AddBranchCommand(name));
        } catch (BranchNameConflictException exception) {
            return exception;
        }
    }

    private Object result(java.util.concurrent.Future<Object> future) throws Exception {
        return future.get();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", exception);
        }
    }
}
