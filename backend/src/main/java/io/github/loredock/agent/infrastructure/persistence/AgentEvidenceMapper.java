package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 运行内证据来源元数据 Mapper；不接收知识或代码正文。 */
@Mapper
public interface AgentEvidenceMapper extends BaseMapper<AgentEvidenceEntity> {
}
