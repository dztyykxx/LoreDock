package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识导入条目结果 Mapper；基础读写复用 MyBatis-Plus Java API。
 */
@Mapper
public interface KnowledgeImportItemMapper extends BaseMapper<KnowledgeImportItemEntity> {
}
