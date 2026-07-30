package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 三个只读工具的有限调用摘要 Mapper。 */
@Mapper
public interface AgentToolCallMapper extends BaseMapper<AgentToolCallEntity> {
}
