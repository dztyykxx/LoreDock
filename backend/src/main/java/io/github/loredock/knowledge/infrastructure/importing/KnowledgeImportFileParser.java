package io.github.loredock.knowledge.infrastructure.importing;

import io.github.loredock.knowledge.application.KnowledgeImportArchiveInvalidException;
import io.github.loredock.knowledge.application.KnowledgeImportCandidate;
import io.github.loredock.knowledge.application.KnowledgeImportTooLargeException;
import io.github.loredock.knowledge.application.KnowledgeImportTypeUnsupportedException;
import io.github.loredock.knowledge.domain.DocumentBody;
import io.github.loredock.knowledge.domain.DocumentDirectory;
import io.github.loredock.knowledge.domain.DocumentFormat;
import io.github.loredock.knowledge.domain.DocumentSource;
import io.github.loredock.knowledge.domain.DocumentSourceType;
import io.github.loredock.knowledge.domain.DocumentTitle;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 不可信上传的计数字节、外层类型与严格 UTF-8 解析器；不解释 Markdown、front matter、HTML 或外部 URL。
 */
@Component
public class KnowledgeImportFileParser {

    private final KnowledgeImportProperties properties;

    /** @param properties 已通过启动校验的资源上限 */
    public KnowledgeImportFileParser(KnowledgeImportProperties properties) {
        this.properties = properties;
    }

    /**
     * 读取上传并按真实字节执行硬上限，同时检查外层扩展名和 ZIP 本地文件头。
     *
     * @param originalFilename 不可信原文件名
     * @param input 上传流
     * @return 已验证载荷
     * @throws KnowledgeImportTooLargeException 真实读取字节超过上限
     * @throws KnowledgeImportTypeUnsupportedException 扩展名不支持
     * @throws KnowledgeImportArchiveInvalidException ZIP 签名无效
     */
    public KnowledgeImportPayload readUpload(String originalFilename, InputStream input) {
        KnowledgeImportFileKind kind = kindOf(originalFilename);
        byte[] bytes = readBounded(input);
        if (kind == KnowledgeImportFileKind.ZIP && !hasZipSignature(bytes)) {
            throw new KnowledgeImportArchiveInvalidException();
        }
        return new KnowledgeImportPayload(originalFilename, kind, bytes);
    }

    /**
     * 把 Markdown 或纯文本单文件转换为草稿候选；BOM 仅作为编码标记移除，其余文本原样保留。
     *
     * @param originalFilename 不可信原文件名
     * @param input 上传流
     * @param directoryPrefix 已校验逻辑目录前缀
     * @param sourceDefaults 仅复用人工整理说明的来源默认值
     * @return 单个导入候选
     */
    public KnowledgeImportCandidate parseSingle(
            String originalFilename,
            InputStream input,
            DocumentDirectory directoryPrefix,
            DocumentSource sourceDefaults
    ) {
        KnowledgeImportPayload payload = readUpload(originalFilename, input);
        return parseSingle(payload, directoryPrefix, sourceDefaults);
    }

    /**
     * 将已经完成上传计数的单文件载荷转换为候选，避免对象存储协调阶段重复读取请求流。
     */
    public KnowledgeImportCandidate parseSingle(
            KnowledgeImportPayload payload,
            DocumentDirectory directoryPrefix,
            DocumentSource sourceDefaults
    ) {
        if (payload.kind() == KnowledgeImportFileKind.ZIP) {
            throw new IllegalArgumentException("ZIP is not a single document");
        }
        String body = decodeUtf8(payload.bytes());
        String displayFilename = basename(payload.originalFilename());
        String title = removeSupportedExtension(displayFilename);
        DocumentFormat format = payload.kind() == KnowledgeImportFileKind.MARKDOWN
                ? DocumentFormat.MARKDOWN
                : DocumentFormat.PLAIN_TEXT;
        DocumentSource source = new DocumentSource(
                DocumentSourceType.UPLOAD, null, payload.originalFilename(), sourceDefaults.curationNote());
        return new KnowledgeImportCandidate(
                0, payload.originalFilename(), format, new DocumentTitle(title), new DocumentBody(body),
                directoryPrefix, source);
    }

    /**
     * 将已通过 ZIP 批次安全校验的 Markdown 普通文件转换为候选；条目路径只用于逻辑目录与展示。
     */
    public KnowledgeImportCandidate parseArchiveEntry(
            int ordinal,
            String originalName,
            String normalizedPath,
            byte[] bytes,
            DocumentDirectory directoryPrefix,
            DocumentSource sourceDefaults
    ) {
        String body = decodeUtf8(bytes);
        String filename = basename(normalizedPath);
        String title = removeSupportedExtension(filename);
        int separator = normalizedPath.lastIndexOf('/');
        String entryDirectory = separator < 0 ? "" : normalizedPath.substring(0, separator);
        String combinedDirectory = combineDirectory(directoryPrefix.value(), entryDirectory);
        DocumentSource source = new DocumentSource(
                DocumentSourceType.UPLOAD, null, originalName, sourceDefaults.curationNote());
        return new KnowledgeImportCandidate(
                ordinal, originalName, DocumentFormat.MARKDOWN,
                new DocumentTitle(title), new DocumentBody(body), new DocumentDirectory(combinedDirectory), source);
    }

    /**
     * 严格解码 UTF-8；任何损坏字节都失败，不使用替换字符或平台默认编码。
     *
     * @param bytes 文本字节
     * @return 移除可选 UTF-8 BOM 的正文
     */
    public String decodeUtf8(byte[] bytes) {
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new InvalidImportTextEncodingException();
        }
    }

    private byte[] readBounded(InputStream input) {
        long limit = properties.maxUploadSize().toBytes();
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > limit) {
                    throw new KnowledgeImportTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("knowledge upload read failed", exception);
        }
    }

    private KnowledgeImportFileKind kindOf(String filename) {
        if (filename == null) {
            throw new KnowledgeImportTypeUnsupportedException();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return KnowledgeImportFileKind.MARKDOWN;
        }
        if (lower.endsWith(".txt")) {
            return KnowledgeImportFileKind.PLAIN_TEXT;
        }
        if (lower.endsWith(".zip")) {
            return KnowledgeImportFileKind.ZIP;
        }
        throw new KnowledgeImportTypeUnsupportedException();
    }

    private boolean hasZipSignature(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            return false;
        }
        return (bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8);
    }

    private String basename(String filename) {
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return filename.substring(slash + 1);
    }

    private String removeSupportedExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        int length = lower.endsWith(".markdown") ? ".markdown".length()
                : lower.endsWith(".txt") ? ".txt".length() : ".md".length();
        return filename.substring(0, filename.length() - length);
    }

    private String combineDirectory(String prefix, String entryDirectory) {
        if (prefix.isEmpty()) {
            return entryDirectory;
        }
        if (entryDirectory.isEmpty()) {
            return prefix;
        }
        return prefix + "/" + entryDirectory;
    }
}
