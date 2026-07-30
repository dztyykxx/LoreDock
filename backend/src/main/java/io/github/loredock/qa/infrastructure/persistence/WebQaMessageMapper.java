package io.github.loredock.qa.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 使用 MyBatis-Plus Java API 访问 Web 问答消息投影。 */
@Mapper
public interface WebQaMessageMapper extends BaseMapper<WebQaMessageEntity> {
}
