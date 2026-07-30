package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
