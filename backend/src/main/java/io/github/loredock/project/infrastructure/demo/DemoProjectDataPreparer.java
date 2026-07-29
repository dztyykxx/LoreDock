package io.github.loredock.project.infrastructure.demo;

import io.github.loredock.project.application.AddBranchCommand;
import io.github.loredock.project.application.AdminProjectDetailView;
import io.github.loredock.project.application.AdminProjectQueryUseCase;
import io.github.loredock.project.application.AdminProjectSummaryView;
import io.github.loredock.project.application.CreateProjectCommand;
import io.github.loredock.project.application.ProjectCommandUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * `demo`/`test` 环境可显式调用的幂等演示主数据准备器。它只写中性项目元数据，不开放生产 HTTP 入口。
 */
@Component
@Profile({"demo", "test"})
public class DemoProjectDataPreparer {

    private static final String NETWORK_IDENTIFIER = "network-designer";
    private static final String COMPARISON_IDENTIFIER = "lightweight-comparison";
    private static final String DEMO_BRANCH = "feature/import-export";

    private final ProjectCommandUseCase commands;
    private final AdminProjectQueryUseCase queries;

    /**
     * @param commands 项目写用例
     * @param queries 可查看全部状态的管理查询用例
     */
    public DemoProjectDataPreparer(ProjectCommandUseCase commands, AdminProjectQueryUseCase queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /**
     * 创建或复用两个验收项目及预期分支。
     *
     * @return 本次新建/复用数量；重复调用不会产生重复记录
     */
    public DemoPreparationReport prepare() {
        Counters counters = new Counters();
        Map<String, AdminProjectSummaryView> existing = new HashMap<>();
        queries.listProjects(null).forEach(project -> existing.put(project.identifier(), project));

        AdminProjectDetailView network = ensureProject(
                existing, NETWORK_IDENTIFIER, "网络设计工具",
                "中性演示项目，仅用于验证项目与分支范围。", "Java 21 + Vue 3", counters);
        ensureBranch(network, DEMO_BRANCH, counters);
        ensureProject(
                existing, COMPARISON_IDENTIFIER, "轻量对照项目",
                "中性小型对照项目，仅用于验收范围隔离。", "Java 21", counters);

        return new DemoPreparationReport(
                counters.createdProjects, counters.reusedProjects,
                counters.createdBranches, counters.reusedBranches);
    }

    private AdminProjectDetailView ensureProject(
            Map<String, AdminProjectSummaryView> existing,
            String identifier,
            String name,
            String description,
            String technologyStack,
            Counters counters
    ) {
        AdminProjectSummaryView found = existing.get(identifier);
        if (found != null) {
            counters.reusedProjects++;
            AdminProjectDetailView detail = queries.getProject(found.id());
            // 每个已存在项目的 main 也计为复用，使报告覆盖完整预期分支集合。
            if (detail.branches().stream().anyMatch(branch -> branch.name().equals("main"))) {
                counters.reusedBranches++;
            }
            return detail;
        }
        AdminProjectDetailView created = commands.createProject(
                new CreateProjectCommand(name, identifier, description, technologyStack));
        counters.createdProjects++;
        counters.createdBranches++;
        return created;
    }

    private void ensureBranch(AdminProjectDetailView project, String name, Counters counters) {
        if (project.branches().stream().anyMatch(branch -> branch.name().equals(name))) {
            counters.reusedBranches++;
            return;
        }
        commands.addBranch(project.id(), new AddBranchCommand(name));
        counters.createdBranches++;
    }

    private static final class Counters {
        private int createdProjects;
        private int reusedProjects;
        private int createdBranches;
        private int reusedBranches;
    }
}
