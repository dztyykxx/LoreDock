package io.github.loredock.knowledgegap.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus Java API 访问知识缺口反馈事实。 */
@Mapper
public interface KnowledgeGapFeedbackMapper extends BaseMapper<KnowledgeGapFeedbackEntity> {
}
