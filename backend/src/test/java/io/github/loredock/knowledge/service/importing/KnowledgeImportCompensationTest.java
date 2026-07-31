package io.github.loredock.knowledge.service.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.loredock.knowledge.config.KnowledgeImportProperties;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.DocumentTags;
import io.github.loredock.knowledge.model.KnowledgeScope;
import io.github.loredock.knowledge.model.command.KnowledgeImportCommand;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.request.KnowledgeImportOptions;
import io.github.loredock.knowledge.service.KnowledgeImportDataService;
import io.github.loredock.knowledge.service.KnowledgeImportItemTransactionService;
import io.github.loredock.platform.persistence.AuditMetadata;
import io.github.loredock.platform.persistence.AuditMetadataFactory;
import io.github.loredock.storage.model.result.StoredObject;
import io.github.loredock.storage.service.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

class KnowledgeImportCompensationTest {

    /**
     * 业务目的：对象已写入但批次记录失败时必须立即发起幂等补偿，不能留下可由导入历史查询到的半批次。
     */
    @Test
    void batchInsertFailureCompensatesStoredObject() {
        ObjectStorage storage = mock(ObjectStorage.class);
        KnowledgeImportDataService repository = mock(KnowledgeImportDataService.class);
        ObjectStorageImportCompensation compensation = mock(ObjectStorageImportCompensation.class);
        when(storage.put(any(), any())).thenReturn(new StoredObject(
                "opaque-key", "guide.md", "text/markdown", 4, "a".repeat(64), Instant.EPOCH));
        org.mockito.Mockito.doThrow(new IllegalStateException("database unavailable"))
                .when(repository).insertBatch(any());
        KnowledgeDocumentImportService service = service(storage, repository, compensation);

        assertThatThrownBy(() -> service.importDocuments(command("body")))
                .isInstanceOf(IllegalStateException.class);

        verify(compensation).deleteUnreferenced("opaque-key");
    }

    /**
     * 业务目的：补偿删除自身失败不能覆盖原始失败，日志也不得包含对象键、文件名或上传正文。
     */
    @Test
    void compensationFailureUsesSafeLogWithoutObjectMetadata() {
        ObjectStorage storage = mock(ObjectStorage.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("failure containing secret-body"))
                .when(storage).delete("secret-object-key");
        ObjectStorageImportCompensation compensation = new ObjectStorageImportCompensation(storage);
        Logger logger = (Logger) LoggerFactory.getLogger(ObjectStorageImportCompensation.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> compensation.deleteUnreferenced("secret-object-key")).doesNotThrowAnyException();
            String rendered = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);
            assertThat(rendered).doesNotContain("secret-object-key", "secret-body");
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            logger.detachAppender(appender);
        }
    }

    private KnowledgeDocumentImportService service(
            ObjectStorage storage,
            KnowledgeImportDataService repository,
            ObjectStorageImportCompensation compensation
    ) {
        KnowledgeImportProperties properties = new KnowledgeImportProperties(
                DataSize.ofKilobytes(10), 10, DataSize.ofKilobytes(2),
                DataSize.ofKilobytes(5), BigDecimal.valueOf(100));
        AuditMetadataFactory auditFactory = mock(AuditMetadataFactory.class);
        AuditMetadata audit = new AuditMetadata(Instant.EPOCH, Instant.EPOCH, "test", "test");
        when(auditFactory.created()).thenReturn(audit);
        return new KnowledgeDocumentImportService(
                new KnowledgeImportFileParser(properties), storage, mock(KnowledgeZipArchiveService.class),
                repository, mock(KnowledgeImportItemTransactionService.class), compensation, auditFactory);
    }

    private KnowledgeImportCommand command(String body) {
        return new KnowledgeImportCommand(
                new io.github.loredock.knowledge.model.request.KnowledgeImportUpload(
                        "guide.md", "text/markdown",
                        new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                new KnowledgeImportOptions(
                        KnowledgeScope.global(), new DocumentDirectory(""), DocumentTags.of(List.of()),
                        new DocumentSource(DocumentSourceType.MANUAL, null, null, null)));
    }
}
