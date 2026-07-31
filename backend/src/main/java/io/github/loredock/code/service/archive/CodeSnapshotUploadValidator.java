package io.github.loredock.code.service.archive;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeSnapshotTooLargeException;
import io.github.loredock.code.exception.CodeSnapshotTypeUnsupportedException;
import io.github.loredock.code.model.result.CodeSnapshotUpload;
import io.github.loredock.code.model.result.ValidatedCodeSnapshotUpload;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 使用扩展名、允许 MIME、ZIP 魔数和实际读取字节共同保护上传边界。
 * 原始文件名只参与类型判断，服务器对象键与工作路径由后续基础设施独立生成。
 */
@Component
public class CodeSnapshotUploadValidator {

    private final CodeSnapshotProperties properties;

    /** @param properties 已通过启动校验的代码资源边界 */
    public CodeSnapshotUploadValidator(CodeSnapshotProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验同步可判定的外层类型和声明大小，并返回仍受真实字节上限保护的同一输入流。
     *
     * @param upload 不可信 multipart 正文
     * @return 可直接交给对象存储流式读取的受限输入
     */
    public ValidatedCodeSnapshotUpload validate(CodeSnapshotUpload upload) {
        if (upload == null || upload.input() == null) {
            throw new CodeSnapshotTypeUnsupportedException();
        }
        long limit = properties.maxUploadSize().toBytes();
        if (upload.declaredSize() > limit) {
            throw new CodeSnapshotTooLargeException();
        }
        if (!hasZipExtension(upload.originalFilename())) {
            throw new CodeSnapshotTypeUnsupportedException();
        }
        String contentType = normalizedContentType(upload.contentType());
        if (!"application/zip".equals(contentType) && !"application/octet-stream".equals(contentType)) {
            throw new CodeSnapshotTypeUnsupportedException();
        }
        PushbackInputStream replayable = new PushbackInputStream(upload.input(), 4);
        byte[] signature = new byte[4];
        try {
            int count = replayable.read(signature);
            if (count > 0) {
                replayable.unread(signature, 0, count);
            }
            if (count != 4 || !hasZipSignature(signature)) {
                throw new CodeSnapshotTypeUnsupportedException();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("code snapshot upload signature read failed", exception);
        }
        return new ValidatedCodeSnapshotUpload(new CountingLimitedInputStream(replayable, limit), contentType);
    }

    private boolean hasZipExtension(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private String normalizedContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        return parameters < 0 ? normalized : normalized.substring(0, parameters).trim();
    }

    private boolean hasZipSignature(byte[] bytes) {
        return bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    /** 只保留累计计数，不缓存正文；超过上限的第一个字节立即终止对象写入。 */
    private static final class CountingLimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private CountingLimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
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

        private void increment(int read) {
            count += read;
            if (count > limit) {
                throw new CodeSnapshotTooLargeException();
            }
        }
    }
}
