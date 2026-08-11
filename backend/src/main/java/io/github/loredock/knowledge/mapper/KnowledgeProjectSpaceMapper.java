package io.github.loredock.knowledge.mapper;

import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 全库检索结果的项目标识批量回填 Mapper；只读取项目主数据的稳定标识。 */
@Mapper
public interface KnowledgeProjectSpaceMapper {

    /** @return 项目 Long 到业务标识的稳定映射 */
    @Select("""
            <script>
            select id, identifier
            from project_space
            where id in
            <foreach collection="projectIds" item="projectId" open="(" separator="," close=")">
              #{projectId}
            </foreach>
            </script>
            """)
    List<ProjectIdentifierRow> selectProjectIdentifiers(@Param("projectIds") Collection<Long> projectIds);

    /** @param id 项目 Long @param identifier 项目业务标识 */
    record ProjectIdentifierRow(Long id, String identifier) {
    }
}
