package io.github.loredock.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.loredock.knowledge.model.entity.KnowledgeDocumentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 知识文档 Mapper；基础读写复用 MyBatis-Plus Java API。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    /**
     * 按 Long 稳定顺序锁行，避免替代发布同时锁两篇文档时产生相反锁序。
     *
     * @param documentIds 非空文档 Long 集合
     * @return 已存在并锁定的文档 Long
     */
    @Select({
            "<script>",
            "select id from knowledge_document where id in",
            "<foreach collection='documentIds' item='documentId' open='(' separator=',' close=')'>",
            "#{documentId}",
            "</foreach>",
            "order by id for update",
            "</script>"
    })
    List<Long> selectIdsForUpdate(@Param("documentIds") List<Long> documentIds);

    /**
     * @param normalizedTag 小写规范化标签名
     * @return 包含该标签的文档标识
     */
    @Select("""
            select id
            from knowledge_document
            where exists (
                select 1
                from jsonb_array_elements(tags::jsonb) tag
                where tag ->> 'normalizedName' = #{normalizedTag}
            )
            """)
    List<Long> selectIdsByTag(@Param("normalizedTag") String normalizedTag);
}
