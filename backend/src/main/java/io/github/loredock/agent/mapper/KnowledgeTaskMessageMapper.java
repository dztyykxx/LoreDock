package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识任务公开消息 Mapper。 */
@Mapper
public interface KnowledgeTaskMessageMapper extends BaseMapper<KnowledgeTaskMessageEntity> {
}
