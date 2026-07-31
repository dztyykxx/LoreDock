package io.github.loredock.code.service.archive;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeSnapshotArchiveInvalidException;
import io.github.loredock.code.exception.CodeSnapshotTooLargeException;
import io.github.loredock.code.model.result.CodeArchiveEntry;
import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.storage.api.ObjectStorage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Commons Compress 中央目录适配器。对象只复制到服务端 Long 工作目录的 {@code input.zip}，
 * 条目名称永远不参与磁盘路径；所有条目先完成结构校验，随后才允许流式读取正文。
 */
@Component
public class CommonsCompressCodeArchiveReader {

    /** 归档结构验证完成后，仅在当前回调期间消费受限条目流。 */
    @FunctionalInterface
    public interface EntryConsumer {
        void accept(CodeArchiveEntry entry, InputStream input) throws IOException;
    }

    private static final byte[] FILE_SIGNATURE = {'P', 'K', 3, 4};
    private static final byte[] EMPTY_SIGNATURE = {'P', 'K', 5, 6};
    private static final byte[] SPANNED_SIGNATURE = {'P', 'K', 7, 8};

    private final ObjectStorage objectStorage;
    private final CodeSnapshotProperties properties;

    /**
     * @param objectStorage 原始 ZIP 对象存储
     * @param properties 工作目录与归档硬上限
     */
    public CommonsCompressCodeArchiveReader(ObjectStorage objectStorage, CodeSnapshotProperties properties) {
        this.objectStorage = objectStorage;
        this.properties = properties;
    }

    public void read(Long jobId, String objectKey, EntryConsumer consumer) {
        Objects.requireNonNull(jobId, "任务 ID 不能为空");
        Objects.requireNonNull(consumer, "条目消费者不能为空");
        Path jobDirectory = null;
        Path inputArchive = null;
        RuntimeException primaryFailure = null;
        try {
            jobDirectory = prepareJobDirectory(jobId);
            inputArchive = jobDirectory.resolve("input.zip");
            try (InputStream input = objectStorage.get(objectKey)) {
                Files.copy(input, inputArchive, StandardCopyOption.REPLACE_EXISTING);
            }
            validateSignature(inputArchive);
            try (ZipFile zip = ZipFile.builder()
                    .setPath(inputArchive)
                    .setMaxNumberOfDisks(1)
                    .get()) {
                List<CodeArchiveEntry> checked = inspectCentralDirectory(zip);
                readCheckedEntries(zip, checked, consumer);
            }
        } catch (EntryConsumerFailure failure) {
            primaryFailure = failure.original();
            throw failure.original();
        } catch (ApplicationException failure) {
            primaryFailure = failure;
            throw failure;
        } catch (IOException | RuntimeException failure) {
            CodeSnapshotArchiveInvalidException sanitized = new CodeSnapshotArchiveInvalidException(failure);
            primaryFailure = sanitized;
            throw sanitized;
        } finally {
            cleanup(inputArchive, jobDirectory, primaryFailure);
        }
    }

    private Path prepareJobDirectory(Long jobId) throws IOException {
        Path root = properties.workRoot().toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path jobDirectory = realRoot.resolve(jobId.toString()).normalize();
        if (!jobDirectory.startsWith(realRoot)) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        Files.createDirectories(jobDirectory);
        if (Files.isSymbolicLink(jobDirectory)) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        return jobDirectory;
    }

    private List<CodeArchiveEntry> inspectCentralDirectory(ZipFile zip) {
        List<CodeArchiveEntry> checked = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        long total = 0;
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            if (checked.size() + 1 > properties.maxArchiveEntries()) {
                throw new CodeSnapshotTooLargeException();
            }
            validateEntryFeatures(zip, entry);
            long expanded = entry.getSize();
            long compressed = entry.getCompressedSize();
            if (expanded < 0 || compressed < 0) {
                throw new CodeSnapshotArchiveInvalidException();
            }
            if (expanded > properties.maxArchiveEntryUncompressedSize().toBytes()) {
                throw new CodeSnapshotTooLargeException();
            }
            try {
                total = Math.addExact(total, expanded);
            } catch (ArithmeticException failure) {
                throw new CodeSnapshotTooLargeException();
            }
            if (total > properties.maxArchiveUncompressedSize().toBytes()) {
                throw new CodeSnapshotTooLargeException();
            }
            validateCompressionRatio(entry, compressed, expanded);
            String path = normalizePath(entry.getName(), entry.isDirectory());
            if (!paths.add(path)) {
                throw new CodeSnapshotArchiveInvalidException();
            }
            validateOrdinaryType(entry);
            checked.add(new CodeArchiveEntry(path, compressed, expanded));
        }
        return List.copyOf(checked);
    }

    private void readCheckedEntries(
            ZipFile zip,
            List<CodeArchiveEntry> checked,
            EntryConsumer consumer
    ) throws IOException {
        Enumeration<ZipArchiveEntry> entries = zip.getEntries();
        int ordinal = 0;
        while (entries.hasMoreElements()) {
            ZipArchiveEntry zipEntry = entries.nextElement();
            CodeArchiveEntry entry = checked.get(ordinal++);
            if (zipEntry.isDirectory()) {
                continue;
            }
            try (InputStream raw = zip.getInputStream(zipEntry);
                 DeclaredSizeInputStream input = new DeclaredSizeInputStream(raw, entry.uncompressedSize())) {
                try {
                    consumer.accept(entry, input);
                } catch (RuntimeException failure) {
                    throw new EntryConsumerFailure(failure);
                }
                input.transferTo(OutputStream.nullOutputStream());
                input.verifyComplete();
            }
        }
    }

    private void validateEntryFeatures(ZipFile zip, ZipArchiveEntry entry) {
        if (entry.getDiskNumberStart() > 0
                || entry.getGeneralPurposeBit().usesEncryption()
                || entry.getGeneralPurposeBit().usesStrongEncryption()
                || !zip.canReadEntryData(entry)) {
            throw new CodeSnapshotArchiveInvalidException();
        }
    }

    private void validateCompressionRatio(ZipArchiveEntry entry, long compressed, long expanded) {
        if (entry.isDirectory() || expanded == 0) {
            return;
        }
        if (compressed == 0) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        BigDecimal ratio = BigDecimal.valueOf(expanded)
                .divide(BigDecimal.valueOf(compressed), 8, RoundingMode.HALF_UP);
        if (ratio.compareTo(properties.maxCompressionRatio()) > 0) {
            throw new CodeSnapshotTooLargeException();
        }
    }

    private void validateOrdinaryType(ZipArchiveEntry entry) {
        if (entry.isUnixSymlink()) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        int type = entry.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
        if (type == 0) {
            return;
        }
        boolean valid = entry.isDirectory() ? type == UnixStat.DIR_FLAG : type == UnixStat.FILE_FLAG;
        if (!valid) {
            throw new CodeSnapshotArchiveInvalidException();
        }
    }

    /**
     * 规范化中央目录逻辑路径；代码 ZIP 对空段、点段和父段全部直接拒绝，不做宽松折叠。
     */
    String normalizePath(String original, boolean directory) {
        if (original == null || original.isBlank() || original.indexOf('\0') >= 0
                || original.indexOf('\\') >= 0 || original.startsWith("/") || isDrivePath(original)) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        String candidate = directory && original.endsWith("/")
                ? original.substring(0, original.length() - 1)
                : original;
        if (candidate.isEmpty() || (!directory && candidate.endsWith("/"))) {
            throw new CodeSnapshotArchiveInvalidException();
        }
        for (String segment : candidate.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new CodeSnapshotArchiveInvalidException();
            }
        }
        return candidate;
    }

    private boolean isDrivePath(String value) {
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
    }

    private void validateSignature(Path archive) throws IOException {
        byte[] signature = new byte[4];
        try (InputStream input = Files.newInputStream(archive)) {
            if (input.readNBytes(signature, 0, signature.length) != signature.length
                    || !(Arrays.equals(signature, FILE_SIGNATURE)
                    || Arrays.equals(signature, EMPTY_SIGNATURE)
                    || Arrays.equals(signature, SPANNED_SIGNATURE))) {
                throw new CodeSnapshotArchiveInvalidException();
            }
        }
    }

    private void cleanup(Path inputArchive, Path jobDirectory, RuntimeException primaryFailure) {
        try {
            if (inputArchive != null) {
                Files.deleteIfExists(inputArchive);
            }
            if (jobDirectory != null) {
                Files.deleteIfExists(jobDirectory);
            }
        } catch (IOException failure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(failure);
            } else {
                throw new CodeSnapshotArchiveInvalidException(failure);
            }
        }
    }

    /** 让消费者自身的业务失败穿过归档适配器，不被错误归类为 ZIP 结构损坏。 */
    private static final class EntryConsumerFailure extends RuntimeException {
        private final RuntimeException original;

        private EntryConsumerFailure(RuntimeException original) {
            super(original);
            this.original = original;
        }

        private RuntimeException original() {
            return original;
        }
    }

    /** 校验实际展开字节必须与中央目录声明完全一致，避免损坏归档被部分读取后误判成功。 */
    private static final class DeclaredSizeInputStream extends FilterInputStream {
        private final long declared;
        private long count;

        private DeclaredSizeInputStream(InputStream input, long declared) {
            super(input);
            this.declared = declared;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(int value) {
            count += value;
            if (count > declared) {
                throw new CodeSnapshotArchiveInvalidException();
            }
        }

        private void verifyComplete() {
            if (count != declared) {
                throw new CodeSnapshotArchiveInvalidException();
            }
        }
    }
}
