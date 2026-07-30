package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus Java API 访问 Web 问答身份事实。 */
@Mapper
public interface WebQaQuestionMapper extends BaseMapper<WebQaQuestionEntity> {
}
