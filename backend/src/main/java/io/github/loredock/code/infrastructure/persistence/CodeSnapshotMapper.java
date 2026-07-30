package io.github.loredock.code.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 代码快照 Mapper；简单持久化使用 MyBatis-Plus，活动切换由后续事务仓储协调。 */
@Mapper
public interface CodeSnapshotMapper extends BaseMapper<CodeSnapshotEntity> {
}
