package io.github.loredock.memory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.memory.model.entity.UserMemoryEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 使用 MyBatis-Plus Java API 访问用户记忆事实；范围过滤必须在 SQL 层闭合。 */
@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemoryEntity> {

    /**
     * 摘要预载的关键词预筛：只取 {@code ACTIVE} 且「GLOBAL ∪ 指定项目」范围内、
     * 任一字段命中关键词的记录，有界取最新 {@code limit} 条（打分在服务层确定性进行）。
     * 范围条件在 SQL 层闭合，防止跨项目记忆进入预载候选。
     */
    @Select("""
            select m.* from user_memory m
            where m.status = 'ACTIVE'
              and (m.scope_type = 'GLOBAL' or m.project_id = #{projectId})
              and (m.title ilike '%' || #{word} || '%'
                   or m.summary ilike '%' || #{word} || '%'
                   or m.content ilike '%' || #{word} || '%')
            order by m.updated_at desc, m.id desc
            limit #{limit}
            """)
    List<UserMemoryEntity> selectKeywordCandidates(
            @Param("projectId") Long projectId,
            @Param("word") String word,
            @Param("limit") int limit);

    /**
     * 无命中兜底：范围内最近使用的高频记忆（按使用频次降序、最近使用时间降序）。
     * 记忆从未被加载过时 last_used_at 为空，排在使用过的之后。
     */
    @Select("""
            select m.* from user_memory m
            where m.status = 'ACTIVE'
              and (m.scope_type = 'GLOBAL' or m.project_id = #{projectId})
            order by m.use_count desc, m.last_used_at desc nulls last, m.id desc
            limit #{limit}
            """)
    List<UserMemoryEntity> selectFallback(@Param("projectId") Long projectId, @Param("limit") int limit);

    /** 单 run 已新写记忆条数：用于 memory_write 的写入预算检查（默认上限 10）。 */
    @Select("select count(*) from user_memory where source_run_id = #{sourceRunId}")
    long countBySourceRun(@Param("sourceRunId") Long sourceRunId);

    /**
     * 全文加载成功后的频次更新：计数与最近使用时间必须在同一语句完成，
     * 防止并发加载互相覆盖计数（读取前仍做可达性校验）。
     */
    @Update("""
            update user_memory
            set use_count = use_count + 1, last_used_at = #{usedAt}
            where id = #{id}
            """)
    int touchUse(@Param("id") Long id, @Param("usedAt") Instant usedAt);
}
