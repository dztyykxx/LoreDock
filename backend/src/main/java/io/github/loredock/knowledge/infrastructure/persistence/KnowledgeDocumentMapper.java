package io.github.loredock.knowledge.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 知识文档 Mapper；基础读写复用 MyBatis-Plus Java API。
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentEntity> {

    /**
     * 按 UUID 稳定顺序锁行，避免替代发布同时锁两篇文档时产生相反锁序。
     *
     * @param documentIds 非空文档 UUID 集合
     * @return 已存在并锁定的文档 UUID
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
    List<UUID> selectIdsForUpdate(@Param("documentIds") List<UUID> documentIds);
}
