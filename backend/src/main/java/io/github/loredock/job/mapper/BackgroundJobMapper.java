package io.github.loredock.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.job.model.entity.BackgroundJobEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 后台任务 Mapper；查询和条件更新由 MyBatis-Plus Java Wrapper 表达。
 */
@Mapper
public interface BackgroundJobMapper extends BaseMapper<BackgroundJobEntity> {
}
