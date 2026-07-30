package io.github.loredock.knowledge.infrastructure.importing;

import io.github.loredock.knowledge.application.KnowledgeImportArchiveInvalidException;
import io.github.loredock.knowledge.application.ZipArchiveEntryInspection;
import io.github.loredock.knowledge.application.ZipArchiveEntryContent;
import io.github.loredock.knowledge.application.ZipArchiveInspection;
import io.github.loredock.knowledge.application.ZipArchiveInspectionPort;
import io.github.loredock.knowledge.application.ZipArchiveReadResult;
import io.github.loredock.knowledge.domain.ImportItemReason;
import io.github.loredock.storage.application.ObjectStorage;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 Commons Compress 中央目录实现 ZIP 安全检查。
 *
 * <p>对象只复制到一个服务生成的临时文件，条目名称从不用于文件系统路径；任何批次级异常都转换为统一的
 * 422 业务语义，避免向调用方暴露对象键、临时路径或解析器细节。</p>
 */
@Component
public class CommonsCompressZipArchiveInspectionPort implements ZipArchiveInspectionPort {

    private static final byte[] LOCAL_FILE_SIGNATURE = {'P', 'K', 3, 4};
    private static final byte[] EMPTY_ARCHIVE_SIGNATURE = {'P', 'K', 5, 6};

    private final ObjectStorage objectStorage;
    private final KnowledgeImportProperties properties;

    /**
     * @param objectStorage 原始上传对象存储
     * @param properties 不可信 ZIP 的资源上限
     */
    public CommonsCompressZipArchiveInspectionPort(
            ObjectStorage objectStorage,
            KnowledgeImportProperties properties
    ) {
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ZipArchiveInspection inspect(String objectKey) {
        return process(objectKey, false).inspection();
    }

    @Override
    public ZipArchiveReadResult inspectAndRead(String objectKey) {
        return process(objectKey, true);
    }

    private ZipArchiveReadResult process(String objectKey, boolean readMarkdown) {
        Path temporaryArchive = null;
        RuntimeException failure = null;
        try {
            temporaryArchive = Files.createTempFile("loredock-knowledge-", ".zip");
            try (InputStream input = objectStorage.get(objectKey)) {
                Files.copy(input, temporaryArchive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            validateSignature(temporaryArchive);
            try (ZipFile zip = ZipFile.builder()
                    .setPath(temporaryArchive)
                    .setMaxNumberOfDisks(1)
                    .get()) {
                ZipArchiveInspection inspection = inspectCentralDirectory(zip);
                List<ZipArchiveEntryContent> contents = readMarkdown
                        ? readMarkdownContents(zip, inspection)
                        : List.of();
                return new ZipArchiveReadResult(inspection, contents);
            }
        } catch (KnowledgeImportArchiveInvalidException exception) {
            failure = exception;
            throw exception;
        } catch (IOException | RuntimeException exception) {
            KnowledgeImportArchiveInvalidException sanitized =
                    new KnowledgeImportArchiveInvalidException(exception);
            failure = sanitized;
            throw sanitized;
        } finally {
            if (temporaryArchive != null) {
                try {
                    Files.deleteIfExists(temporaryArchive);
                } catch (IOException cleanupFailure) {
                    if (failure != null) {
                        failure.addSuppressed(cleanupFailure);
                    } else {
                        throw new KnowledgeImportArchiveInvalidException(cleanupFailure);
                    }
                }
            }
        }
    }

    private List<ZipArchiveEntryContent> readMarkdownContents(
            ZipFile zip,
            ZipArchiveInspection inspection
    ) throws IOException {
        List<ZipArchiveEntryContent> contents = new ArrayList<>();
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        int ordinal = 0;
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            ordinal++;
            ZipArchiveEntryInspection checked = inspection.entries().get(ordinal - 1);
            if (!checked.markdownCandidate()) {
                // 不安全、链接、目录和不支持文件永远不会打开数据流。
                continue;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes(Math.toIntExact(checked.uncompressedSize()) + 1);
                if (bytes.length != checked.uncompressedSize()) {
                    throw new KnowledgeImportArchiveInvalidException();
                }
                contents.add(new ZipArchiveEntryContent(ordinal, bytes));
            }
        }
        return List.copyOf(contents);
    }

    private ZipArchiveInspection inspectCentralDirectory(ZipFile zip) {
        List<ZipArchiveEntryInspection> inspections = new ArrayList<>();
        Set<String> normalizedPaths = new HashSet<>();
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        long totalUncompressedSize = 0;
        int ordinal = 0;
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            ordinal++;
            if (ordinal > properties.maxArchiveEntries()) {
                throw new KnowledgeImportArchiveInvalidException();
            }
            validateReadableMetadata(zip, entry);
            long uncompressedSize = entry.getSize();
            long compressedSize = entry.getCompressedSize();
            if (uncompressedSize < 0 || compressedSize < 0) {
                throw new KnowledgeImportArchiveInvalidException();
            }
            if (uncompressedSize > properties.maxEntryUncompressedSize().toBytes()) {
                throw new KnowledgeImportArchiveInvalidException();
            }
            try {
                totalUncompressedSize = Math.addExact(totalUncompressedSize, uncompressedSize);
            } catch (ArithmeticException exception) {
                throw new KnowledgeImportArchiveInvalidException(exception);
            }
            if (totalUncompressedSize > properties.maxArchiveUncompressedSize().toBytes()) {
                throw new KnowledgeImportArchiveInvalidException();
            }
            validateCompressionRatio(entry, compressedSize, uncompressedSize);

            PathInspection path = normalizeEntryPath(entry.getName());
            if (path.rejectedReason() == null && !normalizedPaths.add(path.normalizedPath())) {
                throw new KnowledgeImportArchiveInvalidException();
            }
            ImportItemReason rejectedReason = path.rejectedReason();
            if (rejectedReason == null && isUnsupportedType(entry)) {
                rejectedReason = ImportItemReason.UNSUPPORTED_ENTRY_TYPE;
            }
            boolean markdownCandidate = rejectedReason == null
                    && !entry.isDirectory()
                    && isMarkdown(path.normalizedPath());
            inspections.add(new ZipArchiveEntryInspection(
                    ordinal,
                    entry.getName(),
                    path.normalizedPath(),
                    compressedSize,
                    uncompressedSize,
                    entry.isDirectory(),
                    markdownCandidate,
                    rejectedReason));
        }
        return new ZipArchiveInspection(List.copyOf(inspections), totalUncompressedSize);
    }

    private void validateReadableMetadata(ZipFile zip, ZipArchiveEntry entry) {
        if (entry.getDiskNumberStart() > 0
                || entry.getGeneralPurposeBit().usesEncryption()
                || entry.getGeneralPurposeBit().usesStrongEncryption()
                || !zip.canReadEntryData(entry)) {
            throw new KnowledgeImportArchiveInvalidException();
        }
    }

    private void validateCompressionRatio(ZipArchiveEntry entry, long compressedSize, long uncompressedSize) {
        if (entry.isDirectory() || uncompressedSize == 0) {
            return;
        }
        if (compressedSize == 0) {
            throw new KnowledgeImportArchiveInvalidException();
        }
        BigDecimal ratio = BigDecimal.valueOf(uncompressedSize)
                .divide(BigDecimal.valueOf(compressedSize), 8, java.math.RoundingMode.HALF_UP);
        if (ratio.compareTo(properties.maxCompressionRatio()) > 0) {
            throw new KnowledgeImportArchiveInvalidException();
        }
    }

    private boolean isUnsupportedType(ZipArchiveEntry entry) {
        if (entry.isUnixSymlink()) {
            return true;
        }
        int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (type == 0) {
            return false;
        }
        return entry.isDirectory() ? type != UnixStat.DIR_FLAG : type != UnixStat.FILE_FLAG;
    }

    private boolean isMarkdown(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private void validateSignature(Path archive) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream input = Files.newInputStream(archive)) {
            if (input.readNBytes(signature, 0, signature.length) != signature.length
                    || (!java.util.Arrays.equals(signature, LOCAL_FILE_SIGNATURE)
                    && !java.util.Arrays.equals(signature, EMPTY_ARCHIVE_SIGNATURE))) {
                throw new KnowledgeImportArchiveInvalidException();
            }
        }
    }

    /**
     * 将中央目录名称规范化为仅用于批次内比较的逻辑路径。
     * 这里不调用 {@link Path#normalize()}，因为驱动器、反斜杠和父级跳转必须按不可信输入显式拒绝。
     */
    PathInspection normalizeEntryPath(String originalName) {
        if (originalName == null || originalName.isBlank()
                || originalName.indexOf('\0') >= 0
                || originalName.startsWith("/")
                || originalName.indexOf('\\') >= 0
                || originalName.matches("^[A-Za-z]:.*")) {
            return PathInspection.rejected();
        }
        List<String> normalizedSegments = new ArrayList<>();
        for (String segment : originalName.split("/", -1)) {
            if (segment.equals("..") || segment.isEmpty() && normalizedSegments.isEmpty()) {
                return PathInspection.rejected();
            }
            if (segment.equals(".") || segment.isEmpty()) {
                continue;
            }
            normalizedSegments.add(segment);
        }
        if (normalizedSegments.isEmpty()) {
            return PathInspection.rejected();
        }
        return new PathInspection(String.join("/", normalizedSegments), null);
    }

    /** 中央目录逻辑路径检查结果；拒绝项不得用于打开数据流。 */
    record PathInspection(String normalizedPath, ImportItemReason rejectedReason) {
        private static PathInspection rejected() {
            return new PathInspection(null, ImportItemReason.UNSAFE_ENTRY_PATH);
        }
    }
}
