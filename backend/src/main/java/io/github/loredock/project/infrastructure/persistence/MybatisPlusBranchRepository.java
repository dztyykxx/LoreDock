package io.github.loredock.project.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.project.application.BranchData;
import io.github.loredock.project.application.BranchRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * MyBatis-Plus 分支仓储适配器；每个查询都先绑定项目 UUID，再处理名称或排序。
 */
@Repository
public class MybatisPlusBranchRepository implements BranchRepository {

    private final ProjectBranchMapper mapper;

    /** @param mapper 分支表 Mapper */
    public MybatisPlusBranchRepository(ProjectBranchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(BranchData branch) {
        mapper.insert(toEntity(branch));
    }

    @Override
    public List<BranchData> findAllByProjectId(UUID projectId) {
        return mapper.selectList(Wrappers.<ProjectBranchEntity>lambdaQuery()
                        .eq(ProjectBranchEntity::getProjectId, projectId)
                        .orderByAsc(ProjectBranchEntity::getName)
                        .orderByAsc(ProjectBranchEntity::getId))
                .stream().map(this::toData).toList();
    }

    @Override
    public Optional<BranchData> findByProjectIdAndName(UUID projectId, String name) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<ProjectBranchEntity>lambdaQuery()
                        .eq(ProjectBranchEntity::getProjectId, projectId)
                        .eq(ProjectBranchEntity::getName, name)))
                .map(this::toData);
    }

    @Override
    public long countByProjectId(UUID projectId) {
        return mapper.selectCount(Wrappers.<ProjectBranchEntity>lambdaQuery()
                .eq(ProjectBranchEntity::getProjectId, projectId));
    }

    private ProjectBranchEntity toEntity(BranchData data) {
        return ProjectBranchEntity.builder()
                .id(data.id())
                .projectId(data.projectId())
                .name(data.name())
                .createdAt(data.createdAt())
                .updatedAt(data.updatedAt())
                .createdBy(data.createdBy())
                .updatedBy(data.updatedBy())
                .build();
    }

    private BranchData toData(ProjectBranchEntity entity) {
        return new BranchData(
                entity.getId(), entity.getProjectId(), entity.getName(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getCreatedBy(), entity.getUpdatedBy()
        );
    }
}
