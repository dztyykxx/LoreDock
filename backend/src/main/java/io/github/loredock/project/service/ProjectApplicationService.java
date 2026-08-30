package io.github.loredock.project.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.project.api.ProjectScope;
import io.github.loredock.project.api.ProjectService;
import io.github.loredock.project.exception.BranchNameConflictException;
import io.github.loredock.project.exception.BranchNotFoundException;
import io.github.loredock.project.exception.ProjectIdentifierConflictException;
import io.github.loredock.project.exception.ProjectNotFoundException;
import io.github.loredock.project.mapper.ProjectBranchMapper;
import io.github.loredock.project.mapper.ProjectSpaceMapper;
import io.github.loredock.project.model.BranchName;
import io.github.loredock.project.model.ProjectDefaults;
import io.github.loredock.project.model.ProjectIdentifier;
import io.github.loredock.project.model.command.AddBranchCommand;
import io.github.loredock.project.model.command.ChangeProjectStatusCommand;
import io.github.loredock.project.model.command.CreateProjectCommand;
import io.github.loredock.project.model.entity.ProjectBranchEntity;
import io.github.loredock.project.model.entity.ProjectSpaceEntity;
import io.github.loredock.project.model.enums.ProjectStatus;
import io.github.loredock.project.model.result.AdminProjectDetailView;
import io.github.loredock.project.model.result.AdminProjectSummaryView;
import io.github.loredock.project.model.result.BranchData;
import io.github.loredock.project.model.result.BranchView;
import io.github.loredock.project.model.result.ProjectData;
import io.github.loredock.project.model.result.ProjectDetailView;
import io.github.loredock.project.model.result.ProjectSummaryView;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务化项目应用服务。项目与 main 在一个事务中写入；普通查询从仓储层即限定启用范围；
 * 只有命名唯一约束会转换为稳定 409，其他数据库异常保留原始失败语义并触发回滚。
 */
@Service
public class ProjectApplicationService implements ProjectService {

    private static final String PROJECT_IDENTIFIER_CONSTRAINT = "uq_project_space_identifier";
    private static final String BRANCH_NAME_CONSTRAINT = "uq_project_branch_project_name";

    private final ProjectSpaceMapper projects;
    private final ProjectBranchMapper branches;
    private final AuditMetadataFactory auditFactory;

    /**
     * @param projects 项目表 Mapper
     * @param branches 分支表 Mapper
     * @param auditFactory UTC 与操作者审计工厂
     */
    public ProjectApplicationService(
            ProjectSpaceMapper projects,
            ProjectBranchMapper branches,
            AuditMetadataFactory auditFactory
    ) {
        this.projects = projects;
        this.branches = branches;
        this.auditFactory = auditFactory;
    }

    @Transactional
    public AdminProjectDetailView createProject(CreateProjectCommand command) {
        Objects.requireNonNull(command, "command");
        String identifier = ProjectIdentifier.of(command.identifier()).value();
        String name = requiredText(command.name(), 100, "project name invalid");
        String description = optionalText(command.description(), 1000, "project description invalid");
        String technologyStack = optionalText(command.technologyStack(), 255, "project technology stack invalid");
        AuditMetadata audit = auditFactory.created();
        ProjectData pendingProject = new ProjectData(
                null, identifier, name, description, technologyStack, ProjectStatus.ENABLED,
                audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()
        );
        try {
            // 两次写入共享 Spring 事务；main 失败时项目插入必须一起回滚，不能产生半成品范围。
            ProjectSpaceEntity projectEntity = toEntity(pendingProject);
            projects.insert(projectEntity);
            Long projectId = requireGeneratedId(projectEntity.getId(), "项目");
            ProjectData project = new ProjectData(
                    projectId, identifier, name, description, technologyStack, ProjectStatus.ENABLED,
                    audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy());
            ProjectBranchEntity branchEntity = toEntity(new BranchData(
                    null, projectId, BranchName.of(ProjectDefaults.DEFAULT_BRANCH).value(),
                    audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()));
            branches.insert(branchEntity);
            BranchData main = new BranchData(
                    requireGeneratedId(branchEntity.getId(), "默认分支"), projectId, branchEntity.getName(),
                    audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy());
            return adminDetail(project, List.of(main));
        } catch (DataIntegrityViolationException exception) {
            if (causedByNamedConstraint(exception, PROJECT_IDENTIFIER_CONSTRAINT)) {
                throw new ProjectIdentifierConflictException(exception);
            }
            throw exception;
        }
    }

    @Transactional
    public BranchView addBranch(Long projectId, AddBranchCommand command) {
        ProjectData project = requireProject(projectId);
        String name = BranchName.of(Objects.requireNonNull(command, "command").name()).value();
        AuditMetadata audit = auditFactory.created();
        BranchData pendingBranch = new BranchData(
                null, project.id(), name,
                audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()
        );
        try {
            ProjectBranchEntity branchEntity = toEntity(pendingBranch);
            branches.insert(branchEntity);
            return branchView(new BranchData(
                    requireGeneratedId(branchEntity.getId(), "项目分支"), project.id(), name,
                    audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()));
        } catch (DataIntegrityViolationException exception) {
            if (causedByNamedConstraint(exception, BRANCH_NAME_CONSTRAINT)) {
                throw new BranchNameConflictException(exception);
            }
            throw exception;
        }
    }

    @Transactional
    public AdminProjectDetailView changeStatus(Long projectId, ChangeProjectStatusCommand command) {
        ProjectData current = requireProject(projectId);
        ProjectStatus target = Objects.requireNonNull(Objects.requireNonNull(command, "command").status(), "status");
        if (current.status() == target) {
            return adminDetail(current, findBranches(current.id()));
        }
        AuditMetadata updatedAudit = auditFactory.updated(new AuditMetadata(
                current.createdAt(), current.updatedAt(), current.createdBy(), current.updatedBy()));
        ProjectData updated = new ProjectData(
                current.id(), current.identifier(), current.name(), current.description(), current.technologyStack(), target,
                updatedAudit.createdAt(), updatedAudit.updatedAt(), updatedAudit.createdBy(), updatedAudit.updatedBy()
        );
        projects.updateById(toEntity(updated));
        return adminDetail(updated, findBranches(updated.id()));
    }

    @Transactional(readOnly = true)
    public List<ProjectSummaryView> listEnabledProjects() {
        return findProjects(ProjectStatus.ENABLED).stream().map(project -> new ProjectSummaryView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                ProjectDefaults.DEFAULT_BRANCH, countBranches(project.id())
        )).toList();
    }

    @Transactional(readOnly = true)
    public ProjectDetailView getEnabledProject(String identifier, String branch) {
        String normalizedIdentifier = ProjectIdentifier.of(identifier).value();
        ProjectData project = findEnabledProject(normalizedIdentifier)
                .orElseThrow(ProjectNotFoundException::new);
        String selectedName = BranchName.of(branch == null ? ProjectDefaults.DEFAULT_BRANCH : branch).value();
        BranchData selected = findBranch(project.id(), selectedName)
                .orElseThrow(BranchNotFoundException::new);
        List<BranchView> branchViews = findBranches(project.id()).stream().map(this::branchView).toList();
        return new ProjectDetailView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                ProjectDefaults.DEFAULT_BRANCH, selected.name(), branchViews
        );
    }

    /**
     * 普通跨模块调用复用现有启用项目查询，并只投影已选分支，避免把 HTTP 管理视图带出模块。
     */
    @Override
    @Transactional(readOnly = true)
    public ProjectScope resolveEnabledScope(String projectIdentifier, String branchName) {
        ProjectDetailView detail = getEnabledProject(projectIdentifier, branchName);
        BranchView selected = detail.branches().stream()
                .filter(branch -> branch.name().equals(detail.selectedBranch()))
                .findFirst()
                .orElseThrow(BranchNotFoundException::new);
        return new ProjectScope(
                detail.id(), detail.identifier(), detail.name(), true, selected.id(), selected.name());
    }

    /**
     * 管理范围直接按项目业务标识解析，允许调用方观察停用状态，但不返回内部状态枚举和审计字段。
     */
    @Override
    @Transactional(readOnly = true)
    public ProjectScope resolveScope(String projectIdentifier, String branchName) {
        String normalizedIdentifier = ProjectIdentifier.of(projectIdentifier).value();
        ProjectData project = findProject(normalizedIdentifier).orElseThrow(ProjectNotFoundException::new);
        BranchData branch = branchName == null
                ? null
                : findBranch(project.id(), BranchName.of(branchName).value())
                        .orElseThrow(BranchNotFoundException::new);
        return scope(project, branch);
    }

    /**
     * 项目级范围解析：与 {@code resolveScope(identifier, null)} 同语义，只校验项目存在并
     * 返回启用标记，分支字段为 null。供仅持有项目主键、范围与分支无关的跨模块流程使用
     * （如项目级长期记忆写入），避免把 null 分支传入
     * {@link #resolveScope(Long, Long)} 触发分支主键非空校验而误拒。
     */
    @Override
    @Transactional(readOnly = true)
    public ProjectScope resolveScope(Long projectId) {
        return scope(requireProject(projectId), null);
    }

    /**
     * 按外键解析时必须同时验证分支归属，防止代码快照把其他项目的分支主键带入当前范围。
     */
    @Override
    @Transactional(readOnly = true)
    public ProjectScope resolveScope(Long projectId, Long branchId) {
        ProjectData project = requireProject(projectId);
        BranchData branch = Optional.ofNullable(branches.selectById(Objects.requireNonNull(branchId, "branchId")))
                .map(this::toData)
                .filter(candidate -> candidate.projectId().equals(project.id()))
                .orElseThrow(BranchNotFoundException::new);
        return scope(project, branch);
    }

    @Transactional(readOnly = true)
    public List<AdminProjectSummaryView> listProjects(ProjectStatus status) {
        return findProjects(status).stream().map(project -> new AdminProjectSummaryView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                project.status(), ProjectDefaults.DEFAULT_BRANCH, countBranches(project.id()),
                project.createdAt(), project.updatedAt(), project.createdBy(), project.updatedBy()
        )).toList();
    }

    @Transactional(readOnly = true)
    public AdminProjectDetailView getProject(Long projectId) {
        ProjectData project = requireProject(projectId);
        return adminDetail(project, findBranches(project.id()));
    }

    private ProjectData requireProject(Long projectId) {
        return Optional.ofNullable(projects.selectById(Objects.requireNonNull(projectId, "projectId")))
                .map(this::toData)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private Optional<ProjectData> findEnabledProject(String identifier) {
        return Optional.ofNullable(projects.selectOne(Wrappers.<ProjectSpaceEntity>lambdaQuery()
                        .eq(ProjectSpaceEntity::getIdentifier, identifier)
                        .eq(ProjectSpaceEntity::getStatus, ProjectStatus.ENABLED.name())))
                .map(this::toData);
    }

    private Optional<ProjectData> findProject(String identifier) {
        return Optional.ofNullable(projects.selectOne(Wrappers.<ProjectSpaceEntity>lambdaQuery()
                        .eq(ProjectSpaceEntity::getIdentifier, identifier)))
                .map(this::toData);
    }

    private List<ProjectData> findProjects(ProjectStatus status) {
        var query = Wrappers.<ProjectSpaceEntity>lambdaQuery();
        if (status != null) {
            query.eq(ProjectSpaceEntity::getStatus, status.name());
        }
        query.orderByAsc(ProjectSpaceEntity::getName).orderByAsc(ProjectSpaceEntity::getId);
        return projects.selectList(query).stream().map(this::toData).toList();
    }

    private List<BranchData> findBranches(Long projectId) {
        return branches.selectList(Wrappers.<ProjectBranchEntity>lambdaQuery()
                        .eq(ProjectBranchEntity::getProjectId, projectId)
                        .orderByAsc(ProjectBranchEntity::getName)
                        .orderByAsc(ProjectBranchEntity::getId))
                .stream().map(this::toData).toList();
    }

    private Optional<BranchData> findBranch(Long projectId, String name) {
        return Optional.ofNullable(branches.selectOne(Wrappers.<ProjectBranchEntity>lambdaQuery()
                        .eq(ProjectBranchEntity::getProjectId, projectId)
                        .eq(ProjectBranchEntity::getName, name)))
                .map(this::toData);
    }

    private long countBranches(Long projectId) {
        return branches.selectCount(Wrappers.<ProjectBranchEntity>lambdaQuery()
                .eq(ProjectBranchEntity::getProjectId, projectId));
    }

    private ProjectSpaceEntity toEntity(ProjectData data) {
        return ProjectSpaceEntity.builder()
                .id(data.id()).identifier(data.identifier()).name(data.name()).description(data.description())
                .technologyStack(data.technologyStack()).status(data.status().name())
                .createdAt(data.createdAt()).updatedAt(data.updatedAt())
                .createdBy(data.createdBy()).updatedBy(data.updatedBy()).build();
    }

    private ProjectBranchEntity toEntity(BranchData data) {
        return ProjectBranchEntity.builder()
                .id(data.id()).projectId(data.projectId()).name(data.name())
                .createdAt(data.createdAt()).updatedAt(data.updatedAt())
                .createdBy(data.createdBy()).updatedBy(data.updatedBy()).build();
    }

    private ProjectData toData(ProjectSpaceEntity entity) {
        return new ProjectData(entity.getId(), entity.getIdentifier(), entity.getName(), entity.getDescription(),
                entity.getTechnologyStack(), ProjectStatus.valueOf(entity.getStatus()), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getCreatedBy(), entity.getUpdatedBy());
    }

    private BranchData toData(ProjectBranchEntity entity) {
        return new BranchData(entity.getId(), entity.getProjectId(), entity.getName(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getCreatedBy(), entity.getUpdatedBy());
    }

    private AdminProjectDetailView adminDetail(ProjectData project, List<BranchData> branchData) {
        return new AdminProjectDetailView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                project.status(), ProjectDefaults.DEFAULT_BRANCH,
                branchData.stream().map(this::branchView).toList(),
                project.createdAt(), project.updatedAt(), project.createdBy(), project.updatedBy()
        );
    }

    private BranchView branchView(BranchData branch) {
        return new BranchView(
                branch.id(), branch.name(), branch.createdAt(), branch.updatedAt(), branch.createdBy(), branch.updatedBy());
    }

    private ProjectScope scope(ProjectData project, BranchData branch) {
        return new ProjectScope(
                project.id(), project.identifier(), project.name(), project.status() == ProjectStatus.ENABLED,
                branch == null ? null : branch.id(), branch == null ? null : branch.name());
    }

    private String requiredText(String value, int maxLength, String error) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(error);
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength, String error) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(error);
        }
        return normalized;
    }

    private boolean causedByNamedConstraint(Throwable failure, String constraintName) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }

    private Long requireGeneratedId(Long id, String aggregate) {
        return Objects.requireNonNull(id, aggregate + "写入后数据库未回填主键");
    }
}
