package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识任务持久化 SSE 事件入口。 */
@Mapper
public interface KnowledgeTaskEventMapper extends BaseMapper<KnowledgeTaskEventEntity> {
}
