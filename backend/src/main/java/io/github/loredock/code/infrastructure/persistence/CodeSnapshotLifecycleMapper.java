package io.github.loredock.code.infrastructure.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.UUID;

/** 代码快照活动切换所需的显式行锁 Mapper。 */
@Mapper
public interface CodeSnapshotLifecycleMapper {

    /** @return 锁定的分支 ID；同行锁串行化该分支的上传激活与重建激活。 */
    @Select("select id from project_branch where id = #{branchId} for update")
    UUID lockBranch(UUID branchId);
}
