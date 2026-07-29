package io.github.loredock.storage.infrastructure.filesystem;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 把规范 UUID 对象键映射到存储根目录内，并拒绝路径穿越和符号链接逃逸。
 */
class SafeObjectPathResolver {

    private static final Pattern OBJECT_KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );
    private final Path root;

    SafeObjectPathResolver(Path configuredRoot) {
        try {
            Path absolute = configuredRoot.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(absolute)) {
                throw invalid("存储根目录不能是符号链接");
            }
            Files.createDirectories(absolute);
            this.root = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new ApplicationException(
                    ErrorCode.STORAGE_WRITE_FAILED,
                    "无法初始化对象存储根目录",
                    exception
            );
        }
    }

    Path root() {
        return root;
    }

    Path resolve(String objectKey) {
        validateKey(objectKey);
        Path parent = root.resolve(objectKey.substring(0, 2)).normalize();
        Path target = parent.resolve(objectKey).normalize();
        if (!target.startsWith(root) || Files.isSymbolicLink(parent) || Files.isSymbolicLink(target)) {
            throw invalid("对象路径试图逃逸存储根目录或包含符号链接");
        }
        return target;
    }

    void ensureParent(Path target) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        if (!parent.startsWith(root) || Files.isSymbolicLink(parent)) {
            throw invalid("对象父目录不是安全的真实目录");
        }
    }

    private void validateKey(String objectKey) {
        if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches()) {
            throw invalid("对象键必须是规范的小写 UUID");
        }
    }

    private ApplicationException invalid(String message) {
        return new ApplicationException(ErrorCode.INVALID_OBJECT_KEY, message);
    }
}
