package io.github.loredock.code.mapper;

import io.github.loredock.code.model.entity.ActiveCodeSnapshotRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

/** 同时约束 ACTIVE snapshot 与 ACTIVE generation 的单次范围查询 Mapper。 */
@Mapper
public interface ActiveCodeSnapshotMapper {

    /** @return 指定分支唯一活动描述行；候选、失败和已退休记录均不会返回。 */
    @Select("""
            select s.project_id, s.branch_id, s.id as snapshot_id, g.id as generation_id,
                   s.commit_hash, s.indexed_at, s.indexed_file_count,
                   p.commit_hash as previous_commit_hash,
                   (select count(*) from code_index_generation history
                    where history.snapshot_id=s.id and history.status in ('ACTIVE','RETIRED'))
                    as successful_generation_count
            from code_snapshot s
            join code_index_generation g on g.snapshot_id=s.id and g.status='ACTIVE'
            left join code_snapshot p on p.id=s.previous_snapshot_id
            where s.branch_id=#{branchId} and s.status='ACTIVE'
            """)
    @Results({
            @Result(column = "project_id", property = "projectId"),
            @Result(column = "branch_id", property = "branchId"),
            @Result(column = "snapshot_id", property = "snapshotId"),
            @Result(column = "generation_id", property = "generationId"),
            @Result(column = "commit_hash", property = "commitHash"),
            @Result(column = "indexed_at", property = "indexedAt"),
            @Result(column = "indexed_file_count", property = "indexedFileCount"),
            @Result(column = "previous_commit_hash", property = "previousCommitHash"),
            @Result(column = "successful_generation_count", property = "successfulGenerationCount")
    })
    ActiveCodeSnapshotRow selectActive(Long branchId);
}
