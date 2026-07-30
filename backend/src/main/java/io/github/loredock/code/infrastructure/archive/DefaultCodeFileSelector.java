package io.github.loredock.code.infrastructure.archive;

import io.github.loredock.code.application.CodeArchiveEntry;
import io.github.loredock.code.application.CodeFileIgnoreReason;
import io.github.loredock.code.application.CodeFileSelection;
import io.github.loredock.code.application.CodeFileSelector;
import io.github.loredock.code.application.CodeSnapshotArchiveInvalidException;
import io.github.loredock.code.infrastructure.CodeSnapshotProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 可审查的默认代码文件选择器。规则排除项不打开额外对象或文件路径；归档端口仍会顺序耗尽当前条目，
 * 因而被忽略文件也不能由调用方绕过选择规则再从原 ZIP 读取。
 */
@Component
public class DefaultCodeFileSelector implements CodeFileSelector {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".hg", ".svn", ".idea", ".gradle", ".cache", "node_modules", "vendor",
            "target", "build", "dist", "out", "bin", "obj", "coverage", ".next", ".nuxt");
    private static final Set<String> SENSITIVE_EXTENSIONS = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".crt", ".cer");
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            ".class", ".jar", ".war", ".ear", ".zip", ".gz", ".tar", ".7z", ".rar",
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".doc", ".docx",
            ".xls", ".xlsx", ".ppt", ".pptx", ".woff", ".woff2", ".ttf", ".otf",
            ".mp3", ".mp4", ".mov", ".avi", ".so", ".dll", ".dylib", ".exe", ".bin");
    private static final Set<String> SENSITIVE_NAMES = Set.of(
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "credentials", "credential",
            "secrets", "secret", "private_key", "apikey", "api_key", "access_token", "auth_token");

    private final long maxFileBytes;

    /** @param properties 已验证的完整单文件文本上限 */
    public DefaultCodeFileSelector(CodeSnapshotProperties properties) {
        this.maxFileBytes = properties.maxIndexedFileSize().toBytes();
    }

    @Override
    public CodeFileSelection select(CodeArchiveEntry entry, InputStream input) {
        String path = entry.path();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String filename = lowerPath.substring(lowerPath.lastIndexOf('/') + 1);
        if (containsExcludedDirectory(lowerPath)) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.EXCLUDED_PATH);
        }
        if (isSensitive(filename)) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.SENSITIVE_PATH);
        }
        if (hasExtension(filename, BINARY_EXTENSIONS)) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.BINARY_FILE_TYPE);
        }
        if (entry.uncompressedSize() > maxFileBytes) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.FILE_TOO_LARGE);
        }
        byte[] bytes = readAtMost(input, maxFileBytes + 1);
        if (bytes.length > maxFileBytes) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.FILE_TOO_LARGE);
        }
        if (looksBinary(bytes)) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.BINARY_CONTENT);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return CodeFileSelection.selected(path, text.startsWith("\uFEFF") ? text.substring(1) : text);
        } catch (CharacterCodingException failure) {
            return CodeFileSelection.ignored(path, CodeFileIgnoreReason.INVALID_UTF8);
        }
    }

    private boolean containsExcludedDirectory(String path) {
        String[] segments = path.split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            if (EXCLUDED_DIRECTORIES.contains(segments[index])) {
                return true;
            }
        }
        return false;
    }

    private boolean isSensitive(String filename) {
        if (".env".equals(filename) || filename.startsWith(".env.")) {
            return true;
        }
        if (hasExtension(filename, SENSITIVE_EXTENSIONS)) {
            return true;
        }
        int extension = filename.indexOf('.');
        String stem = extension < 0 ? filename : filename.substring(0, extension);
        return SENSITIVE_NAMES.contains(stem);
    }

    private boolean hasExtension(String filename, Set<String> extensions) {
        return extensions.stream().anyMatch(filename::endsWith);
    }

    private byte[] readAtMost(InputStream input, long limit) {
        try {
            return input.readNBytes(Math.toIntExact(limit));
        } catch (IOException | ArithmeticException failure) {
            throw new CodeSnapshotArchiveInvalidException(failure);
        }
    }

    private boolean looksBinary(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        int controls = 0;
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            if (unsigned == 0) {
                return true;
            }
            if (unsigned < 0x20 && unsigned != '\n' && unsigned != '\r' && unsigned != '\t') {
                controls++;
            }
        }
        return controls * 10 > bytes.length;
    }
}
