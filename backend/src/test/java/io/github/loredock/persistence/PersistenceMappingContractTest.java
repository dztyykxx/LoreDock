package io.github.loredock.persistence;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.agent.infrastructure.persistence.AgentCitationEntity;
import io.github.loredock.agent.infrastructure.persistence.AgentEvidenceEntity;
import io.github.loredock.agent.infrastructure.persistence.AgentRunEntity;
import io.github.loredock.agent.infrastructure.persistence.AgentRunEventEntity;
import io.github.loredock.agent.infrastructure.persistence.AgentSkillVersionEntity;
import io.github.loredock.agent.infrastructure.persistence.AgentToolCallEntity;
import io.github.loredock.job.infrastructure.persistence.BackgroundJobEntity;
import io.github.loredock.code.infrastructure.persistence.CodeIndexGenerationEntity;
import io.github.loredock.code.infrastructure.persistence.CodeSnapshotEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeDocumentTagEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeImportBatchEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeImportItemEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexDocumentEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchChunkEntity;
import io.github.loredock.knowledge.infrastructure.persistence.KnowledgeSearchGenerationEntity;
import io.github.loredock.knowledgegap.infrastructure.persistence.KnowledgeGapFeedbackCitationEntity;
import io.github.loredock.knowledgegap.infrastructure.persistence.KnowledgeGapFeedbackEntity;
import io.github.loredock.project.infrastructure.persistence.ProjectBranchEntity;
import io.github.loredock.project.infrastructure.persistence.ProjectSpaceEntity;
import io.github.loredock.qa.infrastructure.persistence.WebQaMessageEntity;
import io.github.loredock.qa.infrastructure.persistence.WebQaQuestionEntity;
import io.github.loredock.storage.infrastructure.persistence.StoredObjectEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PersistenceMappingContractTest {

    /**
     * 业务目的：数据库实体必须逐字段绑定 Flyway 列名，防止重命名或全局驼峰配置导致静默读写错误。
     */
    @Test
    void persistenceEntitiesDeclareExplicitTableIdAndFieldMappings() {
        assertExplicitMapping(StoredObjectEntity.class, "stored_object");
        assertExplicitMapping(BackgroundJobEntity.class, "background_job");
        assertExplicitMapping(CodeSnapshotEntity.class, "code_snapshot");
        assertExplicitMapping(CodeIndexGenerationEntity.class, "code_index_generation");
        assertExplicitMapping(ProjectSpaceEntity.class, "project_space");
        assertExplicitMapping(ProjectBranchEntity.class, "project_branch");
        assertExplicitMapping(KnowledgeDocumentEntity.class, "knowledge_document");
        assertExplicitMapping(KnowledgeDocumentTagEntity.class, "knowledge_document_tag");
        assertExplicitMapping(KnowledgeImportBatchEntity.class, "knowledge_import_batch");
        assertExplicitMapping(KnowledgeImportItemEntity.class, "knowledge_import_item");
        assertExplicitMapping(KnowledgeIndexGenerationEntity.class, "knowledge_index_generation");
        assertExplicitMapping(KnowledgeIndexDocumentEntity.class, "knowledge_index_document");
        assertExplicitMapping(KnowledgeSearchGenerationEntity.class, "knowledge_search_generation");
        assertExplicitMapping(KnowledgeSearchChunkEntity.class, "knowledge_search_chunk");
        assertExplicitMapping(AgentSkillVersionEntity.class, "agent_skill_version");
        assertExplicitMapping(AgentRunEntity.class, "agent_run");
        assertExplicitMapping(AgentRunEventEntity.class, "agent_run_event");
        assertExplicitMapping(AgentToolCallEntity.class, "agent_tool_call");
        assertExplicitMapping(AgentEvidenceEntity.class, "agent_evidence");
        assertExplicitMapping(AgentCitationEntity.class, "agent_citation");
        assertExplicitMapping(WebQaQuestionEntity.class, "web_qa_question");
        assertExplicitMapping(WebQaMessageEntity.class, "web_qa_message");
        assertExplicitMapping(KnowledgeGapFeedbackEntity.class, "knowledge_gap_feedback");
        assertExplicitMapping(KnowledgeGapFeedbackCitationEntity.class, "knowledge_gap_feedback_citation");
        System.out.println("测试证据：场景=持久化显式映射，T7新增实体数=4，全部字段均声明表列映射");
    }

    /**
     * 业务目的：SQL 只能在 Java 代码或 Mapper 方法注解中维护，防止 XML 与接口分散后产生行为漂移。
     */
    @Test
    void backendResourcesContainNoMybatisXmlMapper() throws IOException {
        Path resources = Path.of("src/main/resources");
        try (var paths = Files.walk(resources)) {
            assertThat(paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".xml")))
                    .isEmpty();
        }
    }

    private void assertExplicitMapping(Class<?> entityType, String tableName) {
        assertThat(entityType).hasAnnotation(TableName.class);
        assertThat(entityType.getAnnotation(TableName.class).value()).isEqualTo(tableName);

        Field[] persistentFields = Arrays.stream(entityType.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toArray(Field[]::new);
        assertThat(persistentFields).isNotEmpty();
        assertThat(Arrays.stream(persistentFields).filter(field -> field.isAnnotationPresent(TableId.class)))
                .hasSize(1);
        assertThat(Arrays.stream(persistentFields)
                .filter(field -> !field.isAnnotationPresent(TableId.class))
                .allMatch(field -> field.isAnnotationPresent(TableField.class)))
                .isTrue();
    }
}
