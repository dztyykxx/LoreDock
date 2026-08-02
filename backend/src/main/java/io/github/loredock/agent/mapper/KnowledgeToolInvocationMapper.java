package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeToolInvocationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识任务 Tool Invocation 持久化入口。 */
@Mapper
public interface KnowledgeToolInvocationMapper extends BaseMapper<KnowledgeToolInvocationEntity> {
}
