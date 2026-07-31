package io.github.loredock.code.service.archive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.model.enums.CodeFileIgnoreReason;
import io.github.loredock.code.model.result.CodeArchiveEntry;
import io.github.loredock.code.model.result.CodeFileSelection;
import io.github.loredock.code.model.result.CodeFileSelectionSummary;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class DefaultCodeFileSelectorTest {

    private final DefaultCodeFileSelector selector = new DefaultCodeFileSelector(properties(16));

    /**
     * 业务目的：版本控制、依赖、构建和缓存目录必须按完整路径段默认排除，不能因嵌套层级或大小写变化进入索引。
     */
    @Test
    void dependencyBuildCacheAndVersionControlDirectoriesAreIgnoredByStableReason() {
        for (String path : List.of(".git/config", "web/node_modules/pkg/a.js", "server/target/App.class",
                "web/dist/app.js", "module/build/output.txt", "vendor/pkg/source.php", ".cache/item")) {
            assertThat(select(path, bytes("text")).ignoredReason()).isEqualTo(CodeFileIgnoreReason.EXCLUDED_PATH);
        }
        assertThat(select("src/main/App.java", bytes("class App {}"))).satisfies(result -> {
            assertThat(result.selected()).isTrue();
            assertThat(result.text()).isEqualTo("class App {}");
        });
    }

    /**
     * 业务目的：环境文件、证书、私钥和明显凭据文件名必须以安全原因排除，搜索与片段读取不能旁路获得正文。
     */
    @Test
    void environmentCertificatePrivateKeyAndCredentialNamesAreIgnored() {
        for (String path : List.of(".env", ".env.production", "config/server.pem", "cert/client.p12",
                "keys/id_rsa", "config/credentials.json", "deploy/private_key")) {
            assertThat(select(path, bytes("secret-body")).ignoredReason())
                    .isEqualTo(CodeFileIgnoreReason.SENSITIVE_PATH);
        }
    }

    /**
     * 业务目的：超大、NUL、明显二进制和非法 UTF-8 文件必须整项忽略，不能截取前缀伪装成完整代码。
     */
    @Test
    void oversizedBinaryNulAndInvalidUtf8FilesAreIgnoredWithoutPartialText() {
        CodeFileSelection oversized = new DefaultCodeFileSelector(properties(4))
                .select(entry("large.txt", 5), new ByteArrayInputStream(bytes("12345")));
        assertThat(oversized.ignoredReason()).isEqualTo(CodeFileIgnoreReason.FILE_TOO_LARGE);
        assertThat(oversized.text()).isNull();
        assertThat(select("nul.txt", new byte[]{'a', 0, 'b'}).ignoredReason())
                .isEqualTo(CodeFileIgnoreReason.BINARY_CONTENT);
        assertThat(select("control.txt", new byte[]{1, 2, 3, 'a'}).ignoredReason())
                .isEqualTo(CodeFileIgnoreReason.BINARY_CONTENT);
        assertThat(select("bad.txt", new byte[]{(byte) 0xC3, 0x28}).ignoredReason())
                .isEqualTo(CodeFileIgnoreReason.INVALID_UTF8);
        assertThat(select("image.png", bytes("text-looking")).ignoredReason())
                .isEqualTo(CodeFileIgnoreReason.BINARY_FILE_TYPE);
    }

    /**
     * 业务目的：源码与排除文件混合时必须只保留完整允许文本，并按稳定原因汇总忽略计数供任务状态展示。
     */
    @Test
    void mixedFilesProduceCompleteSelectedTextAndStableIgnoreCounts() {
        List<CodeFileSelection> selections = List.of(
                select("src/App.java", bytes("class App {}")),
                select(".env.local", bytes("TOKEN=x")),
                select("target/App.class", bytes("compiled")),
                select("bad.txt", new byte[]{(byte) 0xC3, 0x28}));

        CodeFileSelectionSummary summary = CodeFileSelectionSummary.from(selections);
        assertThat(summary.selected()).extracting(CodeFileSelection::path).containsExactly("src/App.java");
        assertThat(summary.ignoredCounts()).containsEntry(CodeFileIgnoreReason.SENSITIVE_PATH, 1L)
                .containsEntry(CodeFileIgnoreReason.EXCLUDED_PATH, 1L)
                .containsEntry(CodeFileIgnoreReason.INVALID_UTF8, 1L);
        assertThat(summary.totalIgnored()).isEqualTo(3);
    }

    private CodeFileSelection select(String path, byte[] content) {
        return selector.select(entry(path, content.length), new ByteArrayInputStream(content));
    }

    private CodeArchiveEntry entry(String path, long size) {
        return new CodeArchiveEntry(path, size, size);
    }

    private CodeSnapshotProperties properties(long fileLimit) {
        return new CodeSnapshotProperties(
                DataSize.ofMegabytes(1), 100, DataSize.ofMegabytes(1), DataSize.ofBytes(fileLimit),
                DataSize.ofMegabytes(2), BigDecimal.valueOf(100), 1000,
                Path.of("build/work/code"), Path.of("build/indexes/code"));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
