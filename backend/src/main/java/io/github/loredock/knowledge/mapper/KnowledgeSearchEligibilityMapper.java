package io.github.loredock.knowledge.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 当前知识事实表的搜索资格 Mapper；不读取投影正文或候选分数。 */
@Mapper
public interface KnowledgeSearchEligibilityMapper {

    /**
     * 在候选文档 ID 集合内复核当前 PUBLISHED 状态和强范围；范围条件不是候选 SQL 的替代品。
     */
    @Select("""
            <script>
            select id
            from knowledge_document
            where status = 'PUBLISHED'
              and id in
              <foreach collection="candidateIds" item="candidateId" open="(" separator="," close=")">
                #{candidateId}
              </foreach>
              and (
                (#{contextType} = 'GLOBAL' and scope_type = 'GLOBAL')
                or (#{contextType} = 'PROJECT' and (
                    scope_type = 'GLOBAL'
                    or (scope_type = 'PROJECT' and project_id = #{projectId})
                    or (scope_type = 'BRANCH' and project_id = #{projectId} and branch_id = #{branchId})
                ))
                or (#{contextType} = 'ALL' and scope_type in ('GLOBAL', 'PROJECT'))
              )
            </script>
            """)
    List<Long> selectEligibleIds(
            @Param("candidateIds") Collection<Long> candidateIds,
            @Param("contextType") String contextType,
            @Param("projectId") Long projectId,
            @Param("branchId") Long branchId
    );
}
