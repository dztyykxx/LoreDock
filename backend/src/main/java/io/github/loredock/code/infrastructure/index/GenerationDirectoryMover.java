package io.github.loredock.code.infrastructure.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 同一索引根内的原子目录发布操作。 */
@FunctionalInterface
public interface GenerationDirectoryMover {
    /** 把已验证构建目录原子移动为最终 UUID 目录。 */
    void move(Path source, Path target) throws IOException;

    /** @return 不允许降级为非原子移动的默认实现。 */
    static GenerationDirectoryMover atomic() {
        return (source, target) -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
