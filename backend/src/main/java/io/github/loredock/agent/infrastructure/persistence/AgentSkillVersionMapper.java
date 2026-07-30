package io.github.loredock.agent.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 内置 Skill 版本元数据 Mapper；唯一启用与版本冲突由 V6 约束保护。 */
@Mapper
public interface AgentSkillVersionMapper extends BaseMapper<AgentSkillVersionEntity> {
}
