package io.github.loredock.knowledge.service.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.loredock.knowledge.config.KnowledgeImportProperties;
import io.github.loredock.knowledge.exception.InvalidImportTextEncodingException;
import io.github.loredock.knowledge.exception.KnowledgeImportArchiveInvalidException;
import io.github.loredock.knowledge.exception.KnowledgeImportTooLargeException;
import io.github.loredock.knowledge.exception.KnowledgeImportTypeUnsupportedException;
import io.github.loredock.knowledge.model.DocumentDirectory;
import io.github.loredock.knowledge.model.DocumentSource;
import io.github.loredock.knowledge.model.enums.DocumentFormat;
import io.github.loredock.knowledge.model.enums.DocumentSourceType;
import io.github.loredock.knowledge.model.result.KnowledgeImportCandidate;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class KnowledgeImportFileParserTest {

    private final KnowledgeImportProperties properties = new KnowledgeImportProperties(
            DataSize.ofBytes(64), 10, DataSize.ofBytes(32), DataSize.ofBytes(64), BigDecimal.valueOf(10));
    private final KnowledgeImportFileParser parser = new KnowledgeImportFileParser(properties);

    /**
     * 业务目的：Markdown 单文件接受 UTF-8 BOM、按文件名派生标题并保持正文文本语义，不解析 front matter 或 HTML。
     */
    @Test
    void markdownWithBomProducesRawTextCandidateAndDerivedTitle() {
        byte[] body = ("\uFEFF---\ntitle: ignored\n---\n<script>x</script>").getBytes(StandardCharsets.UTF_8);

        KnowledgeImportCandidate candidate = parser.parseSingle(
                "Guide.MD", new ByteArrayInputStream(body), new DocumentDirectory("wiki"), defaults());

        assertThat(candidate.format()).isEqualTo(DocumentFormat.MARKDOWN);
        assertThat(candidate.title().value()).isEqualTo("Guide");
        assertThat(candidate.body().value()).isEqualTo("---\ntitle: ignored\n---\n<script>x</script>");
        assertThat(candidate.directory().value()).isEqualTo("wiki");
        assertThat(candidate.source().type()).isEqualTo(DocumentSourceType.UPLOAD);
        assertThat(candidate.source().originalFilename()).isEqualTo("Guide.MD");
    }

    /**
     * 业务目的：纯文本文件必须保持 HTML 样式字符为普通正文，不能因内容外观改用 Markdown 或执行渲染。
     */
    @Test
    void plainTextRemainsPlainTextWithoutContentSniffing() {
        KnowledgeImportCandidate candidate = parser.parseSingle(
                "notes.txt", stream("<b>plain</b>"), new DocumentDirectory(""), defaults());

        assertThat(candidate.format()).isEqualTo(DocumentFormat.PLAIN_TEXT);
        assertThat(candidate.body().value()).isEqualTo("<b>plain</b>");
    }

    /**
     * 业务目的：非法 UTF-8 必须成为明确失败，禁止平台默认编码替换字节后悄悄创建错误知识。
     */
    @Test
    void invalidUtf8IsRejectedStrictly() {
        assertThatThrownBy(() -> parser.parseSingle(
                "bad.md", new ByteArrayInputStream(new byte[]{(byte) 0xC3, 0x28}),
                new DocumentDirectory(""), defaults()))
                .isInstanceOf(InvalidImportTextEncodingException.class);
    }

    /**
     * 业务目的：不支持的外层扩展名必须在保存批次前按 415 语义拒绝，不能仅信任客户端 MIME。
     */
    @Test
    void unsupportedOuterExtensionIsRejected() {
        assertThatThrownBy(() -> parser.readUpload("document.pdf", stream("%PDF")))
                .isInstanceOf(KnowledgeImportTypeUnsupportedException.class);
    }

    /**
     * 业务目的：上传流必须按真实读取字节执行硬上限，客户端缺失或伪造 Content-Length 不能绕过 413。
     */
    @Test
    void uploadByteLimitStopsOversizedStream() {
        assertThatThrownBy(() -> parser.readUpload("large.md", new ByteArrayInputStream(new byte[65])))
                .isInstanceOf(KnowledgeImportTooLargeException.class);
    }

    /**
     * 业务目的：ZIP 扩展名必须配合合法本地文件头，伪装文本不能进入中央目录解析并泄露解析器错误。
     */
    @Test
    void zipExtensionWithoutSignatureUsesSafeArchiveFailure() {
        assertThatThrownBy(() -> parser.readUpload("archive.zip", stream("not-a-zip")))
                .isInstanceOf(KnowledgeImportArchiveInvalidException.class);
    }

    private ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private DocumentSource defaults() {
        return new DocumentSource(DocumentSourceType.MANUAL, null, null, "curated");
    }
}
