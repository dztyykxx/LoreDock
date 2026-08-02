package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskPublicationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识任务发布幂等事实 Mapper。 */
@Mapper
public interface KnowledgeTaskPublicationMapper extends BaseMapper<KnowledgeTaskPublicationEntity> {
}
