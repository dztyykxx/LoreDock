package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/**
 * 知识导入批次 Mapper；基础读写复用 MyBatis-Plus Java API。
 */
@Mapper
public interface KnowledgeImportBatchMapper extends BaseMapper<KnowledgeImportBatchEntity> {

    /** @return 项目主数据存在时为 true。 */
    @Select("select count(*) > 0 from project_space where id = #{projectId}")
    boolean projectExists(UUID projectId);

    /** @return 分支存在且仍属于项目时为 true。 */
    @Select("select count(*) > 0 from project_branch where id = #{branchId} and project_id = #{projectId}")
    boolean branchBelongsToProject(UUID projectId, UUID branchId);
}
