package io.github.loredock.knowledge.service.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.knowledge.config.KnowledgeImportProperties;
import io.github.loredock.knowledge.exception.KnowledgeImportArchiveInvalidException;
import io.github.loredock.knowledge.model.enums.ImportItemReason;
import io.github.loredock.knowledge.model.snapshot.ZipArchiveInspection;
import io.github.loredock.storage.service.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class CommonsCompressZipArchiveInspectionTest {

    /**
     * 业务目的：安全多级 Markdown、目录和非 Markdown 条目必须按中央目录顺序分类，且不按条目名落盘。
     */
    @Test
    void safeArchiveReturnsOrderedMultiLevelInspection() {
        byte[] archive = zip(
                entry("guides/", new byte[0], UnixStat.DIR_FLAG | 0755, false),
                entry("guides/start.md", bytes("start"), UnixStat.FILE_FLAG | 0644, false),
                entry("guides/deep/more.markdown", bytes("more"), UnixStat.FILE_FLAG | 0644, false),
                entry("image.png", bytes("ignored"), UnixStat.FILE_FLAG | 0644, false));

        ZipArchiveInspection inspection = inspector(archive, limits(10, 100, 300, 100)).inspect("opaque");

        assertThat(inspection.entries()).extracting(entry -> entry.originalName())
                .containsExactly("guides/", "guides/start.md", "guides/deep/more.markdown", "image.png");
        assertThat(inspection.entries()).extracting(entry -> entry.markdownCandidate())
                .containsExactly(false, true, true, false);
        assertThat(inspection.entries()).allSatisfy(entry -> assertThat(entry.rejectedReason()).isNull());
    }

    /**
     * 业务目的：绝对、盘符、父级逃逸和 NUL 路径必须形成不可读取的失败条目，而不是规范化到服务器路径。
     */
    @Test
    void unsafeEntryPathsAreRejectedWithoutReadingContent() {
        KnowledgeZipArchiveService inspector = inspector(
                zip(entry("safe.md", bytes("safe"), UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 100, 300, 100));

        assertThat(inspector.normalizeEntryPath("/absolute.md").rejectedReason())
                .isEqualTo(ImportItemReason.UNSAFE_ENTRY_PATH);
        assertThat(inspector.normalizeEntryPath("C:\\drive.md").rejectedReason())
                .isEqualTo(ImportItemReason.UNSAFE_ENTRY_PATH);
        assertThat(inspector.normalizeEntryPath("a/../escape.md").rejectedReason())
                .isEqualTo(ImportItemReason.UNSAFE_ENTRY_PATH);
        assertThat(inspector.normalizeEntryPath("nul\0name.md").rejectedReason())
                .isEqualTo(ImportItemReason.UNSAFE_ENTRY_PATH);
    }

    /**
     * 业务目的：Unix 符号链接和其他非普通类型必须标为失败且绝不跟随或把链接目标当正文。
     */
    @Test
    void symbolicLinkIsRejectedAsUnsupportedEntryType() {
        byte[] archive = zip(
                entry("linked.md", bytes("../../secret"), UnixStat.LINK_FLAG | 0777, false),
                entry("pipe.md", bytes("not a file"), 0010000 | 0600, false));

        ZipArchiveInspection inspection = inspector(archive, limits(10, 100, 300, 100)).inspect("opaque");

        assertThat(inspection.entries()).hasSize(2).allSatisfy(entry -> {
                    assertThat(entry.rejectedReason()).isEqualTo(ImportItemReason.UNSUPPORTED_ENTRY_TYPE);
                    assertThat(entry.markdownCandidate()).isFalse();
                });
    }

    /**
     * 业务目的：多个中央目录名称规范化到同一路径时必须批次级拒绝，禁止后项覆盖前项。
     */
    @Test
    void duplicateNormalizedPathRejectsWholeArchive() {
        byte[] archive = zip(
                entry("a/./guide.md", bytes("one"), UnixStat.FILE_FLAG | 0644, false),
                entry("a/guide.md", bytes("two"), UnixStat.FILE_FLAG | 0644, false));

        assertThatThrownBy(() -> inspector(archive, limits(10, 100, 300, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);
    }

    /**
     * 业务目的：条目数、单项展开、累计展开和压缩比任一越界都必须在文档事务前中止整个批次。
     */
    @Test
    void everyArchiveResourceLimitRejectsWholeBatch() {
        byte[] twoEntries = zip(
                entry("one.md", bytes("1"), UnixStat.FILE_FLAG | 0644, false),
                entry("two.md", bytes("2"), UnixStat.FILE_FLAG | 0644, false));
        assertThatThrownBy(() -> inspector(twoEntries, limits(1, 100, 300, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);

        byte[] large = zip(entry("large.md", new byte[101], UnixStat.FILE_FLAG | 0644, false));
        assertThatThrownBy(() -> inspector(large, limits(10, 100, 300, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);

        byte[] total = zip(
                entry("one.md", new byte[80], UnixStat.FILE_FLAG | 0644, false),
                entry("two.md", new byte[80], UnixStat.FILE_FLAG | 0644, false));
        assertThatThrownBy(() -> inspector(total, limits(10, 100, 150, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);

        byte[] bomb = zip(entry("bomb.md", new byte[1000], UnixStat.FILE_FLAG | 0644, false));
        assertThatThrownBy(() -> inspector(bomb, limits(10, 2000, 2000, 2)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);
    }

    /**
     * 业务目的：带加密/分卷标志或结构损坏的 ZIP 必须返回统一 422 语义，不泄露解析器细节。
     */
    @Test
    void encryptedAndCorruptedArchivesUseSafeBatchFailure() {
        byte[] encrypted = zip(entry("secret.md", bytes("secret"), UnixStat.FILE_FLAG | 0644, true));
        assertThatThrownBy(() -> inspector(encrypted, limits(10, 100, 300, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class)
                .hasMessage("knowledge import archive invalid");

        byte[] split = zip(entry("part.md", bytes("part"), UnixStat.FILE_FLAG | 0644, false));
        markSplitDisk(split);
        assertThatThrownBy(() -> inspector(split, limits(10, 100, 300, 100)).inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class)
                .hasMessage("knowledge import archive invalid");

        assertThatThrownBy(() -> inspector(new byte[]{'P', 'K', 3, 4, 0}, limits(10, 100, 300, 100))
                .inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class)
                .hasMessage("knowledge import archive invalid");
    }

    /**
     * 业务目的：成功或异常完成检查后都必须删除服务生成的临时 ZIP，避免不可信上传在磁盘积累。
     */
    @Test
    void temporaryArchiveFileIsAlwaysDeleted() throws Exception {
        long before = temporaryFiles();
        inspector(zip(entry("guide.md", bytes("ok"), UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 100, 300, 100)).inspect("opaque");
        assertThat(temporaryFiles()).isEqualTo(before);

        assertThatThrownBy(() -> inspector(new byte[]{'P', 'K', 3, 4, 0}, limits(10, 100, 300, 100))
                .inspect("opaque"))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);
        assertThat(temporaryFiles()).isEqualTo(before);
    }

    private KnowledgeZipArchiveService inspector(byte[] archive, KnowledgeImportProperties properties) {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.get("opaque")).thenAnswer(invocation -> new ByteArrayInputStream(archive));
        return new KnowledgeZipArchiveService(storage, properties);
    }

    private KnowledgeImportProperties limits(int entries, long entryBytes, long totalBytes, long ratio) {
        return new KnowledgeImportProperties(
                DataSize.ofKilobytes(100), entries, DataSize.ofBytes(entryBytes),
                DataSize.ofBytes(totalBytes), BigDecimal.valueOf(ratio));
    }

    private byte[] zip(FixtureEntry... entries) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
                for (FixtureEntry fixture : entries) {
                    ZipArchiveEntry entry = new ZipArchiveEntry(fixture.name());
                    entry.setUnixMode(fixture.unixMode());
                    zip.putArchiveEntry(entry);
                    zip.write(fixture.content());
                    zip.closeArchiveEntry();
                }
            }
            byte[] archive = output.toByteArray();
            if (java.util.Arrays.stream(entries).anyMatch(FixtureEntry::encrypted)) {
                markEncryptionFlag(archive);
            }
            return archive;
        } catch (Exception exception) {
            throw new IllegalStateException("fixture generation failed", exception);
        }
    }

    private void markEncryptionFlag(byte[] archive) {
        for (int index = 0; index <= archive.length - 10; index++) {
            if (archive[index] == 'P' && archive[index + 1] == 'K'
                    && archive[index + 2] == 3 && archive[index + 3] == 4) {
                archive[index + 6] |= 1;
            }
            if (archive[index] == 'P' && archive[index + 1] == 'K'
                    && archive[index + 2] == 1 && archive[index + 3] == 2) {
                archive[index + 8] |= 1;
            }
        }
    }

    private void markSplitDisk(byte[] archive) {
        for (int index = 0; index <= archive.length - 36; index++) {
            if (archive[index] == 'P' && archive[index + 1] == 'K'
                    && archive[index + 2] == 1 && archive[index + 3] == 2) {
                archive[index + 34] = 1;
                return;
            }
        }
        throw new IllegalStateException("central directory fixture missing");
    }

    private FixtureEntry entry(String name, byte[] content, int unixMode, boolean encrypted) {
        return new FixtureEntry(name, content, unixMode, encrypted);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private long temporaryFiles() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(path -> path.getFileName().toString().startsWith("loredock-knowledge-")).count();
        }
    }

    private record FixtureEntry(String name, byte[] content, int unixMode, boolean encrypted) {
    }
}
