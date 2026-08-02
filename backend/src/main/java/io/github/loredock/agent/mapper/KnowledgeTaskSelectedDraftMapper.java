package io.github.loredock.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.agent.model.entity.KnowledgeTaskSelectedDraftEntity;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus 持久化会话固定的待处理草稿。 */
@Mapper
public interface KnowledgeTaskSelectedDraftMapper extends BaseMapper<KnowledgeTaskSelectedDraftEntity> {
}
