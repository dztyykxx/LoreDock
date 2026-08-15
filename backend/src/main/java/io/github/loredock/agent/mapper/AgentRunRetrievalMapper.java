package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.AgentRunRetrievalEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识检索评估记录 Mapper；正文片段仅供评估与审计，不进入公开事件流。 */
@Mapper
public interface AgentRunRetrievalMapper extends BaseMapper<AgentRunRetrievalEntity> {
}
