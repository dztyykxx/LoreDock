package io.github.loredock.code.infrastructure.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 只接收服务端验证过的 generation 直属目录的幂等清理边界。 */
@FunctionalInterface
public interface GenerationDirectoryCleaner {
    /** 幂等删除单个 generation 目录树。 */
    void delete(Path directory) throws IOException;

    /** @return 使用 JDK 文件 API 的递归清理实现。 */
    static GenerationDirectoryCleaner recursive() {
        return directory -> {
            if (!Files.exists(directory)) {
                return;
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        };
    }
}
