package io.github.loredock.knowledge.infrastructure.persistence;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** 候选 SQL 的只读行模型；仅承载固定 generation 内构造引用所需的投影字段。 */
@Getter
@Setter
public class KnowledgeSearchCandidateRow {

    private UUID documentId;
    private Integer chunkNo;
    private Integer startOffset;
    private Integer endOffset;
    private String content;
    private String title;
    private String tagsJson;
    private String sourceType;
    private String wikiUrl;
    private String originalFilename;
    private String curationNote;
    private String scopeType;
    private UUID projectId;
    private UUID branchId;
    private String format;
    private Instant sourceUpdatedAt;
    private Double rawScore;
}
