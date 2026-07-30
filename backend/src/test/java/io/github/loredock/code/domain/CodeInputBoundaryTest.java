package io.github.loredock.code.domain;

import io.github.loredock.code.application.CodeSnapshotTooLargeException;
import io.github.loredock.code.application.CodeSnapshotTypeUnsupportedException;
import io.github.loredock.code.application.CodeSnapshotUpload;
import io.github.loredock.code.infrastructure.CodeSnapshotProperties;
import io.github.loredock.code.infrastructure.archive.CodeSnapshotUploadValidator;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeInputBoundaryTest {

    private final CodeSnapshotProperties properties = new CodeSnapshotProperties(
            DataSize.ofBytes(8), 10, DataSize.ofBytes(32), DataSize.ofBytes(4), DataSize.ofBytes(64),
            BigDecimal.TEN, 100, Path.of("build/work/code"), Path.of("build/indexes/code"));
    private final CodeSnapshotUploadValidator uploads = new CodeSnapshotUploadValidator(properties);

    /**
     * 业务目的：commit 只保存去除首尾空白后的规范小写十六进制，防止无效声明被展示成已确认版本事实。
     */
    @Test
    void commitNormalizesValidHexAndRejectsMissingOrInvalidValues() {
        assertThat(new CodeCommit(" ABCDEF1 ").value()).isEqualTo("abcdef1");
        assertThat(new CodeCommit("a".repeat(64)).value()).hasSize(64);

        for (String invalid : new String[]{null, "", "abcdef", "g123456", "a".repeat(65)}) {
            assertThatThrownBy(() -> new CodeCommit(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 业务目的：仓库路径必须是与操作系统无关的规范相对路径，防止搜索、片段和 ZIP 条目把输入解释成服务器路径。
     */
    @Test
    void repositoryPathAcceptsCanonicalRelativePathAndRejectsTraversalForms() {
        assertThat(new RepositoryRelativePath("src/main/App.java").value()).isEqualTo("src/main/App.java");

        for (String invalid : new String[]{null, "", "/etc/passwd", "C:/secret", "src\\App.java",
                "src//App.java", "src/./App.java", "src/../App.java", "src/\0App.java", "src/"}) {
            assertThatThrownBy(() -> new RepositoryRelativePath(invalid))
                    .as(String.valueOf(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 业务目的：ZIP 扩展名、允许 MIME 与魔数必须同时一致，防止仅信任客户端文件名或 Content-Type 接收伪装正文。
     */
    @Test
    void zipExtensionMimeAndMagicMustAgreeBeforeObjectPersistence() throws Exception {
        byte[] zip = new byte[]{'P', 'K', 3, 4, 1, 2};
        var validated = uploads.validate(new CodeSnapshotUpload(
                new ByteArrayInputStream(zip), "source.ZIP", "application/octet-stream", zip.length));
        assertThat(validated.input().readAllBytes()).containsExactly(zip);

        assertUnsupported("source.txt", "application/zip", zip);
        assertUnsupported("source.zip", "text/plain", zip);
        assertUnsupported("source.zip", "application/zip", new byte[]{1, 2, 3, 4});
    }

    /**
     * 业务目的：上传限制必须按对象存储实际读取字节执行，缺失或伪造声明大小都不能绕过 413，也不能把整包先读入内存。
     */
    @Test
    void countingStreamStopsAtConfiguredByteLimitRegardlessOfDeclaredSize() throws Exception {
        byte[] oversized = new byte[]{'P', 'K', 3, 4, 1, 2, 3, 4, 5};
        var validated = uploads.validate(new CodeSnapshotUpload(
                new ByteArrayInputStream(oversized), "source.zip", "application/zip", -1));

        assertThatThrownBy(() -> validated.input().readAllBytes())
                .isInstanceOf(CodeSnapshotTooLargeException.class);
        assertThatThrownBy(() -> uploads.validate(new CodeSnapshotUpload(
                new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4}),
                "source.zip", "application/zip", 9)))
                .isInstanceOf(CodeSnapshotTooLargeException.class);
    }

    private void assertUnsupported(String name, String contentType, byte[] bytes) {
        assertThatThrownBy(() -> uploads.validate(new CodeSnapshotUpload(
                new ByteArrayInputStream(bytes), name, contentType, bytes.length)))
                .isInstanceOf(CodeSnapshotTypeUnsupportedException.class);
    }
}
