package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识索引 generation Mapper；激活切换由仓储适配器在事务中完成。
 */
@Mapper
public interface KnowledgeIndexGenerationMapper extends BaseMapper<KnowledgeIndexGenerationEntity> {
}
