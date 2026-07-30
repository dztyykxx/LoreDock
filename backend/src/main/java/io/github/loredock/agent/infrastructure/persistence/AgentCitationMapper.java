package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 最终可信引用 Mapper；同运行复合外键由 V6 兜底。 */
@Mapper
public interface AgentCitationMapper extends BaseMapper<AgentCitationEntity> {
}
