package io.github.loredock.project.controller;

import io.github.loredock.project.converter.ProjectHttpMapper;
import io.github.loredock.project.model.response.ProjectDetailResponse;
import io.github.loredock.project.model.response.ProjectSummaryResponse;
import io.github.loredock.project.service.ProjectApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN 与 MEMBER 共用的普通项目查询入口，只委托已在仓储范围内排除停用项目的应用用例。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectApplicationService projects;

    /** @param projects 普通项目查询用例 */
    public ProjectController(ProjectApplicationService projects) {
        this.projects = projects;
    }

    /**
     * @return 按名称稳定排序的已启用项目，不包含知识数或快照等 T2 未实现数据
     */
    @GetMapping
    public List<ProjectSummaryResponse> listProjects() {
        return projects.listEnabledProjects().stream().map(ProjectHttpMapper::toResponse).toList();
    }

    /**
     * 查询已启用项目并明确解析当前分支。
     *
     * @param identifier 项目稳定业务标识
     * @param branch 可选分支；省略时由应用用例选择 main
     * @return 项目详情、所选分支和项目内全部分支
     */
    @GetMapping("/{identifier}")
    public ProjectDetailResponse getProject(
            @PathVariable String identifier,
            @RequestParam(required = false) String branch
    ) {
        return ProjectHttpMapper.toResponse(projects.getEnabledProject(identifier, branch));
    }
}
