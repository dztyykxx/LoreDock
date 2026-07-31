package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.loredock.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识索引 generation Mapper；激活切换由仓储适配器在事务中完成。
 */
@Mapper
public interface KnowledgeIndexGenerationMapper extends BaseMapper<KnowledgeIndexGenerationEntity> {

    /**
     * 删除已经进入失败或取消终态任务遗留的 BUILDING generation；外键负责级联投影与检索数据。
     *
     * @return 删除的 generation 数量
     */
    @Delete("""
            delete from knowledge_index_generation generation
            using background_job job
            where generation.job_id = job.id
              and generation.status = 'BUILDING'
              and job.status in ('FAILED', 'CANCELLED')
            """)
    int deleteAbandonedBuildingGenerations();

    /**
     * @return 当前唯一活动的完整知识索引 generation
     */
    default KnowledgeIndexGenerationEntity selectActive() {
        // 使用 BaseMapper 生成式查询而非 select * 注解 SQL，确保显式列映射与 selectById 一致。
        return selectOne(Wrappers.<KnowledgeIndexGenerationEntity>lambdaQuery()
                .eq(KnowledgeIndexGenerationEntity::getStatus, "ACTIVE"));
    }
}
