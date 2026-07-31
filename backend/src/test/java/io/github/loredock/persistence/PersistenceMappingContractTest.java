package io.github.loredock.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.loredock.agent.model.entity.AgentEvidenceEntity;
import io.github.loredock.agent.model.entity.AgentRunEntity;
import io.github.loredock.agent.model.entity.AgentRunEventEntity;
import io.github.loredock.code.model.entity.CodeIndexGenerationEntity;
import io.github.loredock.code.model.entity.CodeSnapshotEntity;
import io.github.loredock.feedback.model.entity.KnowledgeGapFeedbackCitationEntity;
import io.github.loredock.feedback.model.entity.KnowledgeGapFeedbackEntity;
import io.github.loredock.job.model.entity.BackgroundJobEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeDocumentEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeImportBatchEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import io.github.loredock.knowledge.model.entity.KnowledgeSearchChunkEntity;
import io.github.loredock.project.model.entity.ProjectBranchEntity;
import io.github.loredock.project.model.entity.ProjectSpaceEntity;
import io.github.loredock.qa.model.entity.WebQaMessageEntity;
import io.github.loredock.qa.model.entity.WebQaQuestionEntity;
import io.github.loredock.storage.model.entity.StoredObjectEntity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

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
        assertExplicitMapping(KnowledgeImportBatchEntity.class, "knowledge_import_batch");
        assertExplicitMapping(KnowledgeIndexGenerationEntity.class, "knowledge_index_generation");
        assertExplicitMapping(KnowledgeSearchChunkEntity.class, "knowledge_search_chunk");
        assertExplicitMapping(AgentRunEntity.class, "agent_run");
        assertExplicitMapping(AgentRunEventEntity.class, "agent_run_event");
        assertExplicitMapping(AgentEvidenceEntity.class, "agent_evidence");
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
