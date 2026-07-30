package io.github.loredock.knowledge.infrastructure.importing;

import io.github.loredock.knowledge.application.ImportObjectCompensation;
import io.github.loredock.knowledge.application.KnowledgeDocumentImportUseCase;
import io.github.loredock.knowledge.application.KnowledgeImportBatchNotFoundException;
import io.github.loredock.knowledge.application.KnowledgeImportBatchRecord;
import io.github.loredock.knowledge.application.KnowledgeImportBatchView;
import io.github.loredock.knowledge.application.KnowledgeImportCandidate;
import io.github.loredock.knowledge.application.KnowledgeImportCommand;
import io.github.loredock.knowledge.application.KnowledgeImportItemRecord;
import io.github.loredock.knowledge.application.KnowledgeImportItemTransactionService;
import io.github.loredock.knowledge.application.KnowledgeImportItemView;
import io.github.loredock.knowledge.application.KnowledgeImportOptions;
import io.github.loredock.knowledge.application.KnowledgeImportRepository;
import io.github.loredock.knowledge.application.KnowledgeScopeInvalidException;
import io.github.loredock.knowledge.application.ZipArchiveEntryContent;
import io.github.loredock.knowledge.application.ZipArchiveEntryInspection;
import io.github.loredock.knowledge.application.ZipArchiveInspectionPort;
import io.github.loredock.knowledge.application.ZipArchiveReadResult;
import io.github.loredock.knowledge.domain.ImportBatchStatus;
import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.knowledge.domain.ImportItemStatus;
import io.github.loredock.platform.audit.AuditMetadata;
import io.github.loredock.platform.audit.AuditMetadataFactory;
import io.github.loredock.storage.application.ObjectStorage;
import io.github.loredock.storage.domain.ObjectMetadata;
import io.github.loredock.storage.domain.StoredObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 同步知识导入协调器：先限制并存储原始对象，再完成批次级 ZIP 安全检查，最后逐项独立事务创建草稿。
 */
@Service
public class KnowledgeDocumentImportService implements KnowledgeDocumentImportUseCase {

    private final KnowledgeImportFileParser parser;
    private final ObjectStorage objectStorage;
    private final ZipArchiveInspectionPort archives;
    private final KnowledgeImportRepository imports;
    private final KnowledgeImportItemTransactionService itemTransactions;
    private final ImportObjectCompensation compensation;
    private final AuditMetadataFactory auditFactory;

    /** 创建导入协调器。 */
    public KnowledgeDocumentImportService(
            KnowledgeImportFileParser parser,
            ObjectStorage objectStorage,
            ZipArchiveInspectionPort archives,
            KnowledgeImportRepository imports,
            KnowledgeImportItemTransactionService itemTransactions,
            ImportObjectCompensation compensation,
            AuditMetadataFactory auditFactory
    ) {
        this.parser = parser;
        this.objectStorage = objectStorage;
        this.archives = archives;
        this.imports = imports;
        this.itemTransactions = itemTransactions;
        this.compensation = compensation;
        this.auditFactory = auditFactory;
    }

    @Override
    public KnowledgeImportBatchView importDocuments(KnowledgeImportCommand command) {
        KnowledgeImportPayload payload = parser.readUpload(
                command.upload().originalFilename(), command.upload().content());
        StoredObject stored = objectStorage.put(
                new ByteArrayInputStream(payload.bytes()),
                new ObjectMetadata(payload.originalFilename(), contentType(command)));

        ZipArchiveReadResult archive = null;
        if (payload.kind() == KnowledgeImportFileKind.ZIP) {
            try {
                archive = archives.inspectAndRead(stored.objectKey());
            } catch (RuntimeException exception) {
                compensation.deleteUnreferenced(stored.objectKey());
                throw exception;
            }
        }

        AuditMetadata audit = auditFactory.created();
        UUID batchId = UUID.randomUUID();
        KnowledgeImportBatchRecord batch = new KnowledgeImportBatchRecord(
                batchId, stored.objectKey(), payload.originalFilename(), command.options().scope(),
                command.options().directoryPrefix().value(), ImportBatchStatus.FAILED,
                0, 0, 0, audit);
        try {
            imports.insertBatch(batch);
        } catch (RuntimeException exception) {
            compensation.deleteUnreferenced(stored.objectKey());
            throw exception;
        }

        List<KnowledgeImportItemRecord> results = payload.kind() == KnowledgeImportFileKind.ZIP
                ? processArchive(batchId, archive, command.options())
                : processSingle(batchId, payload, command.options());
        Summary summary = summarize(results);
        AuditMetadata updated = auditFactory.updated(audit);
        imports.updateSummary(batchId, summary.status(), summary.succeeded(), summary.failed(), summary.ignored(),
                updated.updatedAt(), updated.updatedBy());
        return getBatch(batchId);
    }

    @Override
    public KnowledgeImportBatchView getBatch(UUID batchId) {
        KnowledgeImportBatchRecord batch = imports.findBatch(batchId)
                .orElseThrow(KnowledgeImportBatchNotFoundException::new);
        List<KnowledgeImportItemView> items = imports.findItems(batchId).stream()
                .sorted(Comparator.comparingInt(KnowledgeImportItemRecord::ordinal))
                .map(KnowledgeImportItemRecord::toView)
                .toList();
        return new KnowledgeImportBatchView(
                batch.id(), batch.originalFilename(), batch.scope(), batch.directoryPrefix(), batch.status(),
                batch.succeededCount(), batch.failedCount(), batch.ignoredCount(), items,
                batch.audit().createdAt(), batch.audit().createdBy());
    }

    private List<KnowledgeImportItemRecord> processSingle(
            UUID batchId,
            KnowledgeImportPayload payload,
            KnowledgeImportOptions options
    ) {
        try {
            KnowledgeImportCandidate candidate = parser.parseSingle(payload, options.directoryPrefix(),
                    options.sourceDefaults());
            return List.of(createOrRecordFailure(batchId, candidate, options));
        } catch (InvalidImportTextEncodingException exception) {
            return List.of(itemTransactions.record(
                    batchId, 0, payload.originalFilename(), ImportItemStatus.FAILED,
                    ImportItemReason.INVALID_TEXT_ENCODING, "文件不是有效 UTF-8 文本"));
        } catch (IllegalArgumentException exception) {
            return List.of(itemTransactions.record(
                    batchId, 0, payload.originalFilename(), ImportItemStatus.FAILED,
                    ImportItemReason.INVALID_DOCUMENT_FIELDS, "文件名、正文或目录不符合文档规则"));
        }
    }

    private List<KnowledgeImportItemRecord> processArchive(
            UUID batchId,
            ZipArchiveReadResult archive,
            KnowledgeImportOptions options
    ) {
        Map<Integer, byte[]> contentByOrdinal = new HashMap<>();
        for (ZipArchiveEntryContent content : archive.contents()) {
            contentByOrdinal.put(content.ordinal(), content.bytes());
        }
        boolean hasMarkdown = archive.inspection().entries().stream()
                .anyMatch(ZipArchiveEntryInspection::markdownCandidate);
        List<KnowledgeImportItemRecord> results = new ArrayList<>();
        for (ZipArchiveEntryInspection entry : archive.inspection().entries()) {
            if (entry.rejectedReason() != null) {
                results.add(itemTransactions.record(
                        batchId, entry.ordinal(), entry.originalName(), ImportItemStatus.FAILED,
                        entry.rejectedReason(), rejectedMessage(entry.rejectedReason())));
                continue;
            }
            if (!entry.markdownCandidate()) {
                ImportItemReason reason = hasMarkdown
                        ? ImportItemReason.UNSUPPORTED_FILE_TYPE
                        : ImportItemReason.NO_IMPORTABLE_DOCUMENTS;
                results.add(itemTransactions.record(
                        batchId, entry.ordinal(), entry.originalName(), ImportItemStatus.IGNORED,
                        reason, hasMarkdown ? "目录或非 Markdown 文件已忽略" : "归档中没有可导入的 Markdown"));
                continue;
            }
            try {
                KnowledgeImportCandidate candidate = parser.parseArchiveEntry(
                        entry.ordinal(), entry.originalName(), entry.normalizedPath(),
                        contentByOrdinal.get(entry.ordinal()), options.directoryPrefix(), options.sourceDefaults());
                results.add(createOrRecordFailure(batchId, candidate, options));
            } catch (InvalidImportTextEncodingException exception) {
                results.add(itemTransactions.record(
                        batchId, entry.ordinal(), entry.originalName(), ImportItemStatus.FAILED,
                        ImportItemReason.INVALID_TEXT_ENCODING, "文件不是有效 UTF-8 文本"));
            } catch (IllegalArgumentException exception) {
                results.add(itemTransactions.record(
                        batchId, entry.ordinal(), entry.originalName(), ImportItemStatus.FAILED,
                        ImportItemReason.INVALID_DOCUMENT_FIELDS, "文件名、正文或目录不符合文档规则"));
            }
        }
        return List.copyOf(results);
    }

    private KnowledgeImportItemRecord createOrRecordFailure(
            UUID batchId,
            KnowledgeImportCandidate candidate,
            KnowledgeImportOptions options
    ) {
        try {
            return itemTransactions.createDraft(batchId, candidate, options);
        } catch (KnowledgeScopeInvalidException exception) {
            return itemTransactions.record(
                    batchId, candidate.ordinal(), candidate.entryName(), ImportItemStatus.FAILED,
                    ImportItemReason.DOCUMENT_SCOPE_INVALID, "所选项目或分支已失效");
        } catch (IllegalArgumentException exception) {
            return itemTransactions.record(
                    batchId, candidate.ordinal(), candidate.entryName(), ImportItemStatus.FAILED,
                    ImportItemReason.INVALID_DOCUMENT_FIELDS, "文件名、正文或目录不符合文档规则");
        } catch (RuntimeException exception) {
            return itemTransactions.record(
                    batchId, candidate.ordinal(), candidate.entryName(), ImportItemStatus.FAILED,
                    ImportItemReason.DOCUMENT_PERSISTENCE_FAILED, "文档保存失败，请检查后重试");
        }
    }

    private Summary summarize(List<KnowledgeImportItemRecord> results) {
        int succeeded = (int) results.stream().filter(item -> item.status() == ImportItemStatus.SUCCEEDED).count();
        int failed = (int) results.stream().filter(item -> item.status() == ImportItemStatus.FAILED).count();
        int ignored = (int) results.stream().filter(item -> item.status() == ImportItemStatus.IGNORED).count();
        ImportBatchStatus status = succeeded == 0
                ? ImportBatchStatus.FAILED
                : failed == 0 && ignored == 0 ? ImportBatchStatus.COMPLETED : ImportBatchStatus.PARTIAL;
        return new Summary(status, succeeded, failed, ignored);
    }

    private String contentType(KnowledgeImportCommand command) {
        String value = command.upload().contentType();
        return value == null || value.isBlank() ? "application/octet-stream" : value;
    }

    private String rejectedMessage(ImportItemReason reason) {
        return reason == ImportItemReason.UNSAFE_ENTRY_PATH
                ? "条目路径不安全，未读取正文"
                : "条目不是受支持的普通文件，未读取正文";
    }

    private record Summary(ImportBatchStatus status, int succeeded, int failed, int ignored) {
    }
}
