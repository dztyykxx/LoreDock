package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftRevisionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 不可变草稿修订 Mapper。 */
@Mapper
public interface KnowledgeDraftRevisionMapper extends BaseMapper<KnowledgeDraftRevisionEntity> {
}
