package io.github.loredock.project.infrastructure.web;

import io.github.loredock.project.application.AddBranchCommand;
import io.github.loredock.project.application.AdminProjectQueryUseCase;
import io.github.loredock.project.application.ChangeProjectStatusCommand;
import io.github.loredock.project.application.CreateProjectCommand;
import io.github.loredock.project.application.ProjectCommandUseCase;
import io.github.loredock.project.domain.ProjectStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 管理员项目维护入口。角色授权由统一 `/api/admin/**` 服务端拦截链执行，Controller 不信任客户端角色字段。
 */
@RestController
@RequestMapping("/api/admin/projects")
public class AdminProjectController {

    private final ProjectCommandUseCase commands;
    private final AdminProjectQueryUseCase queries;

    /**
     * @param commands 管理写用例
     * @param queries 保留停用状态的管理查询用例
     */
    public AdminProjectController(ProjectCommandUseCase commands, AdminProjectQueryUseCase queries) {
        this.commands = commands;
        this.queries = queries;
    }

    /** @param status 可选状态过滤 @return 包含启用与停用语义的管理摘要 */
    @GetMapping
    public List<AdminProjectSummaryResponse> listProjects(@RequestParam(required = false) ProjectStatus status) {
        return queries.listProjects(status).stream().map(ProjectHttpMapper::toResponse).toList();
    }

    /** @param projectId 项目 UUID @return 含全部分支与审计的管理详情 */
    @GetMapping("/{projectId}")
    public AdminProjectDetailResponse getProject(@PathVariable UUID projectId) {
        return ProjectHttpMapper.toResponse(queries.getProject(projectId));
    }

    /**
     * 创建项目及默认 main 分支。重复标识由应用/数据库边界映射为 409。
     *
     * @param request 已完成字段级校验的创建请求
     * @return 创建后的管理详情
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProjectDetailResponse createProject(@Valid @RequestBody CreateProjectRequest request) {
        return ProjectHttpMapper.toResponse(commands.createProject(new CreateProjectCommand(
                request.name(), request.identifier(), request.description(), request.technologyStack())));
    }

    /**
     * @param projectId 所属项目 UUID
     * @param request 分支名请求
     * @return 新分支
     */
    @PostMapping("/{projectId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse addBranch(
            @PathVariable UUID projectId,
            @Valid @RequestBody AddBranchRequest request
    ) {
        return ProjectHttpMapper.toResponse(commands.addBranch(projectId, new AddBranchCommand(request.name())));
    }

    /**
     * 幂等设置项目状态；停用只改变普通查询可见性，不删除分支。
     *
     * @param projectId 项目 UUID
     * @param request 目标状态
     * @return 当前管理详情
     */
    @PatchMapping("/{projectId}/status")
    public AdminProjectDetailResponse changeStatus(
            @PathVariable UUID projectId,
            @Valid @RequestBody ChangeProjectStatusRequest request
    ) {
        return ProjectHttpMapper.toResponse(commands.changeStatus(
                projectId, new ChangeProjectStatusCommand(request.status())));
    }
}
