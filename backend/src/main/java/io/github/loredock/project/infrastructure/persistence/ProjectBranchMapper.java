package io.github.loredock.project.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目分支 Mapper；所有查询条件必须携带项目 UUID 以保持范围隔离。
 */
@Mapper
public interface ProjectBranchMapper extends BaseMapper<ProjectBranchEntity> {
}
