package io.github.loredock.knowledgegap.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus Java API 访问反馈引用的规范化关联。 */
@Mapper
public interface KnowledgeGapFeedbackCitationMapper extends BaseMapper<KnowledgeGapFeedbackCitationEntity> {
}
