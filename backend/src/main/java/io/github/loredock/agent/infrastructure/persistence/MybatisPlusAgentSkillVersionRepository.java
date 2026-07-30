package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.agent.application.AgentSkillVersionMetadata;
import io.github.loredock.agent.application.AgentSkillVersionRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** MyBatis-Plus Skill 元数据仓储，退役旧版与发布新版位于同一短事务。 */
@Repository
public class MybatisPlusAgentSkillVersionRepository implements AgentSkillVersionRepository {

    private final AgentSkillVersionMapper mapper;

    /** @param mapper Skill 版本 Mapper */
    public MybatisPlusAgentSkillVersionRepository(AgentSkillVersionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentSkillVersionMetadata> findByNameAndVersion(String name, String version) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                        .eq(AgentSkillVersionEntity::getSkillName, name)
                        .eq(AgentSkillVersionEntity::getSkillVersion, version)))
                .map(this::metadata);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentSkillVersionMetadata> findEnabled(String name) {
        return Optional.ofNullable(mapper.selectOne(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                        .eq(AgentSkillVersionEntity::getSkillName, name)
                        .eq(AgentSkillVersionEntity::getStatus, "ENABLED")))
                .map(this::metadata);
    }

    @Override
    @Transactional
    public void publish(AgentSkillVersionMetadata metadata) {
        mapper.update(Wrappers.<AgentSkillVersionEntity>lambdaUpdate()
                .eq(AgentSkillVersionEntity::getSkillName, metadata.name())
                .eq(AgentSkillVersionEntity::getStatus, "ENABLED")
                .set(AgentSkillVersionEntity::getStatus, "RETIRED"));
        mapper.insert(AgentSkillVersionEntity.builder()
                .id(metadata.id()).skillName(metadata.name()).skillVersion(metadata.version())
                .contentHash(metadata.contentHash()).objectKey(metadata.objectKey())
                .outputSchemaVersion(metadata.outputSchemaVersion()).status("ENABLED")
                .createdAt(metadata.createdAt()).build());
    }

    private AgentSkillVersionMetadata metadata(AgentSkillVersionEntity value) {
        return new AgentSkillVersionMetadata(value.getId(), value.getSkillName(), value.getSkillVersion(),
                value.getContentHash(), value.getObjectKey(), value.getOutputSchemaVersion(), value.getStatus(),
                value.getCreatedAt());
    }
}
