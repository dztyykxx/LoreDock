package io.github.loredock.project.application;

import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import io.github.loredock.project.domain.BranchName;
import io.github.loredock.project.domain.ProjectDefaults;
import io.github.loredock.project.domain.ProjectIdentifier;
import io.github.loredock.project.domain.ProjectStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 事务化项目应用服务。项目与 main 在一个事务中写入；普通查询从仓储层即限定启用范围；
 * 只有命名唯一约束会转换为稳定 409，其他数据库异常保留原始失败语义并触发回滚。
 */
@Service
public class ProjectApplicationService
        implements ProjectCommandUseCase, ProjectQueryUseCase, AdminProjectQueryUseCase {

    private static final String PROJECT_IDENTIFIER_CONSTRAINT = "uq_project_space_identifier";
    private static final String BRANCH_NAME_CONSTRAINT = "uq_project_branch_project_name";

    private final ProjectRepository projects;
    private final BranchRepository branches;
    private final AuditMetadataFactory auditFactory;

    /**
     * @param projects 项目仓储端口
     * @param branches 分支仓储端口
     * @param auditFactory UTC 与操作者审计工厂
     */
    public ProjectApplicationService(
            ProjectRepository projects,
            BranchRepository branches,
            AuditMetadataFactory auditFactory
    ) {
        this.projects = projects;
        this.branches = branches;
        this.auditFactory = auditFactory;
    }

    @Override
    @Transactional
    public AdminProjectDetailView createProject(CreateProjectCommand command) {
        Objects.requireNonNull(command, "command");
        String identifier = ProjectIdentifier.of(command.identifier()).value();
        String name = requiredText(command.name(), 100, "project name invalid");
        String description = optionalText(command.description(), 1000, "project description invalid");
        String technologyStack = optionalText(command.technologyStack(), 255, "project technology stack invalid");
        AuditMetadata audit = auditFactory.created();
        UUID projectId = UUID.randomUUID();
        ProjectData project = new ProjectData(
                projectId, identifier, name, description, technologyStack, ProjectStatus.ENABLED,
                audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()
        );
        BranchData main = new BranchData(
                UUID.randomUUID(), projectId, BranchName.of(ProjectDefaults.DEFAULT_BRANCH).value(),
                audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()
        );
        try {
            // 两次写入共享 Spring 事务；main 失败时项目插入必须一起回滚，不能产生半成品范围。
            projects.insert(project);
            branches.insert(main);
        } catch (DataIntegrityViolationException exception) {
            if (causedByNamedConstraint(exception, PROJECT_IDENTIFIER_CONSTRAINT)) {
                throw new ProjectIdentifierConflictException(exception);
            }
            throw exception;
        }
        return adminDetail(project, List.of(main));
    }

    @Override
    @Transactional
    public BranchView addBranch(UUID projectId, AddBranchCommand command) {
        ProjectData project = requireProject(projectId);
        String name = BranchName.of(Objects.requireNonNull(command, "command").name()).value();
        AuditMetadata audit = auditFactory.created();
        BranchData branch = new BranchData(
                UUID.randomUUID(), project.id(), name,
                audit.createdAt(), audit.updatedAt(), audit.createdBy(), audit.updatedBy()
        );
        try {
            branches.insert(branch);
        } catch (DataIntegrityViolationException exception) {
            if (causedByNamedConstraint(exception, BRANCH_NAME_CONSTRAINT)) {
                throw new BranchNameConflictException(exception);
            }
            throw exception;
        }
        return branchView(branch);
    }

    @Override
    @Transactional
    public AdminProjectDetailView changeStatus(UUID projectId, ChangeProjectStatusCommand command) {
        ProjectData current = requireProject(projectId);
        ProjectStatus target = Objects.requireNonNull(Objects.requireNonNull(command, "command").status(), "status");
        if (current.status() == target) {
            return adminDetail(current, branches.findAllByProjectId(current.id()));
        }
        AuditMetadata updatedAudit = auditFactory.updated(new AuditMetadata(
                current.createdAt(), current.updatedAt(), current.createdBy(), current.updatedBy()));
        ProjectData updated = new ProjectData(
                current.id(), current.identifier(), current.name(), current.description(), current.technologyStack(), target,
                updatedAudit.createdAt(), updatedAudit.updatedAt(), updatedAudit.createdBy(), updatedAudit.updatedBy()
        );
        projects.update(updated);
        return adminDetail(updated, branches.findAllByProjectId(updated.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectSummaryView> listEnabledProjects() {
        return projects.findAllEnabled().stream().map(project -> new ProjectSummaryView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                ProjectDefaults.DEFAULT_BRANCH, branches.countByProjectId(project.id())
        )).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectDetailView getEnabledProject(String identifier, String branch) {
        String normalizedIdentifier = ProjectIdentifier.of(identifier).value();
        ProjectData project = projects.findEnabledByIdentifier(normalizedIdentifier)
                .orElseThrow(ProjectNotFoundException::new);
        String selectedName = BranchName.of(branch == null ? ProjectDefaults.DEFAULT_BRANCH : branch).value();
        BranchData selected = branches.findByProjectIdAndName(project.id(), selectedName)
                .orElseThrow(BranchNotFoundException::new);
        List<BranchView> branchViews = branches.findAllByProjectId(project.id()).stream().map(this::branchView).toList();
        return new ProjectDetailView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                ProjectDefaults.DEFAULT_BRANCH, selected.name(), branchViews
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminProjectSummaryView> listProjects(ProjectStatus status) {
        return projects.findAll(status).stream().map(project -> new AdminProjectSummaryView(
                project.id(), project.identifier(), project.name(), project.description(), project.technologyStack(),
                project.status(), ProjectDefaults.DEFAULT_BRANCH, branches.countByProjectId(project.id()),
                project.createdAt(), project.updatedAt(), project.createdBy(), project.updatedBy()
        )).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminProjectDetailView getProject(UUID projectId) {
        ProjectData project = requireProject(projectId);
        return adminDetail(project, branches.findAllByProjectId(project.id()));
    }

    private ProjectData requireProject(UUID projectId) {
        return projects.findById(Objects.requireNonNull(projectId, "projectId"))
                .orElseThrow(ProjectNotFoundException::new);
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
}
