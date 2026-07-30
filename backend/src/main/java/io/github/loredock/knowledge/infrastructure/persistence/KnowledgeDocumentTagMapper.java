package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档标签 Mapper；批量替换标签时由仓储适配器统一控制事务。
 */
@Mapper
public interface KnowledgeDocumentTagMapper extends BaseMapper<KnowledgeDocumentTagEntity> {
}
