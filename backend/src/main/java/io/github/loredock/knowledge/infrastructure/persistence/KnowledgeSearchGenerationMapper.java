package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 检索 generation 元数据 Mapper；一对一与计数约束由 V5 数据库结构保护。 */
@Mapper
public interface KnowledgeSearchGenerationMapper extends BaseMapper<KnowledgeSearchGenerationEntity> {
}
