package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeDraftRevisionSourceEntity;
import org.apache.ibatis.annotations.Mapper;

/** 草稿修订来源 Mapper。 */
@Mapper
public interface KnowledgeDraftRevisionSourceMapper extends BaseMapper<KnowledgeDraftRevisionSourceEntity> {
}
