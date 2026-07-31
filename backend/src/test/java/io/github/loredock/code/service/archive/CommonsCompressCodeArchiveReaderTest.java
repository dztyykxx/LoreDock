package io.github.loredock.code.service.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeSnapshotArchiveInvalidException;
import io.github.loredock.code.exception.CodeSnapshotTooLargeException;
import io.github.loredock.storage.service.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class CommonsCompressCodeArchiveReaderTest {

    @TempDir
    private Path temporaryDirectory;

    /**
     * 业务目的：任何路径形态风险都必须按逻辑仓库路径拒绝，不能依赖当前操作系统的 Path 规范化差异。
     */
    @Test
    void pathValidatorRejectsAbsoluteDriveBackslashEmptyDotParentAndNulSegments() {
        CommonsCompressCodeArchiveReader reader = reader(new byte[0], limits(20, 100, 1000, 100));

        for (String invalid : new String[]{"/absolute.java", "C:/drive.java", "src\\App.java",
                "src//App.java", "src/./App.java", "src/../App.java", "src/\0App.java", ""}) {
            assertThatThrownBy(() -> reader.normalizePath(invalid, false))
                    .as(invalid)
                    .isInstanceOf(CodeSnapshotArchiveInvalidException.class);
        }
        assertThat(reader.normalizePath("src/main/App.java", false)).isEqualTo("src/main/App.java");
        assertThat(reader.normalizePath("src/main/", true)).isEqualTo("src/main");
    }

    /**
     * 业务目的：必须先校验完整中央目录再打开首个代码流，防止把恶意后置条目隐藏在已读取或默认忽略区域。
     */
    @Test
    void wholeArchiveValidationFinishesBeforeAnyEntryContentIsRead() {
        byte[] archive = zip(
                entry("src/Safe.java", bytes("safe"), UnixStat.FILE_FLAG | 0644, false),
                entry("node_modules/../escape.js", bytes("evil"), UnixStat.FILE_FLAG | 0644, false));
        List<String> opened = new ArrayList<>();

        assertThatThrownBy(() -> reader(archive, limits(20, 100, 1000, 100))
                .read(8000000000000000150L, "opaque", (entry, input) -> opened.add(entry.path())))
                .isInstanceOf(CodeSnapshotArchiveInvalidException.class);
        assertThat(opened).isEmpty();
    }

    /**
     * 业务目的：规范化重复、符号链接和特殊 Unix 条目必须让整个候选失败，不能只忽略风险项后激活部分索引。
     */
    @Test
    void duplicatePathSymlinkAndSpecialEntryRejectWholeCandidate() {
        byte[] duplicate = zip(
                entry("src/App.java", bytes("one"), UnixStat.FILE_FLAG | 0644, false),
                entry("src/App.java", bytes("two"), UnixStat.FILE_FLAG | 0644, false));
        assertInvalid(duplicate);
        assertInvalid(zip(entry("link", bytes("target"), UnixStat.LINK_FLAG | 0777, false)));
        assertInvalid(zip(entry("pipe", bytes("pipe"), 0010000 | 0600, false)));
    }

    /**
     * 业务目的：条目数、声明单项/累计展开量和压缩比任一超限都必须终止候选，防止压缩炸弹耗尽后台资源。
     */
    @Test
    void entryCountDeclaredSizesAndCompressionRatioAreHardLimits() {
        byte[] two = zip(
                entry("one.java", bytes("1"), UnixStat.FILE_FLAG | 0644, false),
                entry("two.java", bytes("2"), UnixStat.FILE_FLAG | 0644, false));
        assertTooLarge(two, limits(1, 100, 1000, 100));
        assertTooLarge(zip(entry("large.java", new byte[101], UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 100, 1000, 100));
        assertTooLarge(zip(
                        entry("one.java", new byte[80], UnixStat.FILE_FLAG | 0644, false),
                        entry("two.java", new byte[80], UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 100, 150, 100));
        assertTooLarge(zip(entry("bomb.java", new byte[1000], UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 2000, 2000, 2));
    }

    /**
     * 业务目的：加密、分卷、损坏 ZIP 和成功读取都必须关闭归档并在 finally 清理服务生成的 input.zip。
     */
    @Test
    void encryptedSplitCorruptAndSuccessfulArchivesAlwaysCleanTemporaryInput() throws Exception {
        byte[] encrypted = zip(entry("secret.java", bytes("secret"), UnixStat.FILE_FLAG | 0644, true));
        assertInvalid(encrypted);
        byte[] split = zip(entry("part.java", bytes("part"), UnixStat.FILE_FLAG | 0644, false));
        markSplitDisk(split);
        assertInvalid(split);
        assertInvalid(new byte[]{'P', 'K', 3, 4, 0});

        List<String> contents = new ArrayList<>();
        CommonsCompressCodeArchiveReader reader = reader(
                zip(entry("src/App.java", bytes("class App {}"), UnixStat.FILE_FLAG | 0644, false)),
                limits(10, 100, 1000, 100));
        reader.read(8000000000000000151L, "opaque", (entry, input) ->
                contents.add(new String(input.readAllBytes(), StandardCharsets.UTF_8)));
        assertThat(contents).containsExactly("class App {}");
        try (var paths = Files.walk(temporaryDirectory)) {
            assertThat(paths.filter(path -> path.getFileName().toString().equals("input.zip"))).isEmpty();
        }
    }

    private void assertInvalid(byte[] archive) {
        assertThatThrownBy(() -> reader(archive, limits(20, 2000, 4000, 100))
                .read(8000000000000000152L, "opaque", (entry, input) -> input.readAllBytes()))
                .isInstanceOf(CodeSnapshotArchiveInvalidException.class);
    }

    private void assertTooLarge(byte[] archive, CodeSnapshotProperties properties) {
        assertThatThrownBy(() -> reader(archive, properties)
                .read(8000000000000000153L, "opaque", (entry, input) -> input.readAllBytes()))
                .isInstanceOf(CodeSnapshotTooLargeException.class);
    }

    private CommonsCompressCodeArchiveReader reader(byte[] archive, CodeSnapshotProperties properties) {
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.get("opaque")).thenAnswer(invocation -> new ByteArrayInputStream(archive));
        return new CommonsCompressCodeArchiveReader(storage, properties);
    }

    private CodeSnapshotProperties limits(int entries, long fileBytes, long totalBytes, long ratio) {
        return new CodeSnapshotProperties(
                DataSize.ofMegabytes(1), entries, DataSize.ofBytes(fileBytes),
                DataSize.ofBytes(Math.min(fileBytes, 100)), DataSize.ofBytes(totalBytes),
                BigDecimal.valueOf(ratio), 100, temporaryDirectory.resolve("work"),
                temporaryDirectory.resolve("indexes"));
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

    private record FixtureEntry(String name, byte[] content, int unixMode, boolean encrypted) {
    }
}
