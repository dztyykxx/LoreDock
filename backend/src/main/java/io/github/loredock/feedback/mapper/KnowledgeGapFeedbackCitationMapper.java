package io.github.loredock.feedback.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.feedback.model.entity.KnowledgeGapFeedbackCitationEntity;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus Java API 访问反馈引用的规范化关联。 */
@Mapper
public interface KnowledgeGapFeedbackCitationMapper extends BaseMapper<KnowledgeGapFeedbackCitationEntity> {
}
