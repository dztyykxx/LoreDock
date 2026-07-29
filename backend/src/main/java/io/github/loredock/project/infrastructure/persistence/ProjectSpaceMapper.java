package io.github.loredock.project.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目空间 Mapper；简单读写和范围过滤统一使用 MyBatis-Plus Java API。
 */
@Mapper
public interface ProjectSpaceMapper extends BaseMapper<ProjectSpaceEntity> {
}
