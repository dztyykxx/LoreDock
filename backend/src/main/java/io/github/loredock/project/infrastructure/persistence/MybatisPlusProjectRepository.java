package io.github.loredock.project.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.project.application.ProjectData;
import io.github.loredock.project.application.ProjectRepository;
import io.github.loredock.project.domain.ProjectStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus 项目仓储适配器。普通查询在 SQL 条件中直接限定 ENABLED，避免停用元数据先被跨范围加载。
 */
@Repository
public class MybatisPlusProjectRepository implements ProjectRepository {

    private final ProjectSpaceMapper mapper;

    /** @param mapper 项目表 Mapper */
    public MybatisPlusProjectRepository(ProjectSpaceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ProjectData project) {
        mapper.insert(toEntity(project));
    }

    @Override
    public void update(ProjectData project) {
        mapper.updateById(toEntity(project));
    }

    @Override
    public Optional<ProjectData> findById(UUID projectId) {
        return Optional.ofNullable(mapper.selectById(projectId)).map(this::toData);
    }

    @Override
    public Optional<ProjectData> findEnabledByIdentifier(String identifier) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ProjectSpaceEntity>lambdaQuery()
                        .eq(ProjectSpaceEntity::getIdentifier, identifier)
                        .eq(ProjectSpaceEntity::getStatus, ProjectStatus.ENABLED.name())))
                .map(this::toData);
    }

    @Override
    public List<ProjectData> findAllEnabled() {
        return mapper.selectList(Wrappers.<ProjectSpaceEntity>lambdaQuery()
                        .eq(ProjectSpaceEntity::getStatus, ProjectStatus.ENABLED.name())
                        .orderByAsc(ProjectSpaceEntity::getName)
                        .orderByAsc(ProjectSpaceEntity::getId))
                .stream().map(this::toData).toList();
    }

    @Override
    public List<ProjectData> findAll(ProjectStatus status) {
        var query = Wrappers.<ProjectSpaceEntity>lambdaQuery();
        if (status != null) {
            query.eq(ProjectSpaceEntity::getStatus, status.name());
        }
        query.orderByAsc(ProjectSpaceEntity::getName).orderByAsc(ProjectSpaceEntity::getId);
        return mapper.selectList(query).stream().map(this::toData).toList();
    }

    private ProjectSpaceEntity toEntity(ProjectData data) {
        return ProjectSpaceEntity.builder()
                .id(data.id())
                .identifier(data.identifier())
                .name(data.name())
                .description(data.description())
                .technologyStack(data.technologyStack())
                .status(data.status().name())
                .createdAt(data.createdAt())
                .updatedAt(data.updatedAt())
                .createdBy(data.createdBy())
                .updatedBy(data.updatedBy())
                .build();
    }

    private ProjectData toData(ProjectSpaceEntity entity) {
        return new ProjectData(
                entity.getId(), entity.getIdentifier(), entity.getName(), entity.getDescription(),
                entity.getTechnologyStack(), ProjectStatus.valueOf(entity.getStatus()),
                entity.getCreatedAt(), entity.getUpdatedAt(), entity.getCreatedBy(), entity.getUpdatedBy()
        );
    }
}
