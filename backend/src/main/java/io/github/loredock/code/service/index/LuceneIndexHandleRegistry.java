package io.github.loredock.code.service.index;

import io.github.loredock.code.config.CodeSnapshotProperties;
import io.github.loredock.code.exception.CodeGenerationPublishException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * generation reader 引用计数注册表。退休后禁止新引用，已有请求释放最后引用前不关闭 reader 或删除目录。
 */
@Component
public class LuceneIndexHandleRegistry {

    /** 退休索引目录清理的模块内部故障注入点。 */
    @FunctionalInterface
    interface DirectoryCleaner {
        void delete(Path directory) throws IOException;

        static DirectoryCleaner recursive() {
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

    private static final Logger LOGGER = LoggerFactory.getLogger(LuceneIndexHandleRegistry.class);

    private final Path indexRoot;
    private final LuceneIndexHandleRegistry.DirectoryCleaner cleaner;
    private final Map<Long, Entry> entries = new HashMap<>();

    /** @param properties 仅使用已验证索引根 */
    @Autowired
    public LuceneIndexHandleRegistry(CodeSnapshotProperties properties) {
        this(properties.indexRoot(), LuceneIndexHandleRegistry.DirectoryCleaner.recursive());
    }

    public LuceneIndexHandleRegistry(Path indexRoot) {
        this(indexRoot, LuceneIndexHandleRegistry.DirectoryCleaner.recursive());
    }

    LuceneIndexHandleRegistry(Path indexRoot, LuceneIndexHandleRegistry.DirectoryCleaner cleaner) {
        this.indexRoot = indexRoot.toAbsolutePath().normalize();
        this.cleaner = cleaner;
    }

    /** 获取固定 generation reader；物理路径只能由 Long 在索引根下派生。 */
    public synchronized LuceneIndexHandle acquire(Long generationId) {
        Entry entry = entries.get(generationId);
        if (entry == null) {
            entry = open(generationId);
            entries.put(generationId, entry);
        }
        if (entry.retired) {
            throw new IllegalStateException("retired generation cannot accept new readers");
        }
        entry.references++;
        Entry acquired = entry;
        return new LuceneIndexHandle(entry.reader, () -> release(generationId, acquired));
    }

    /** 标记 generation 退休；无引用时立即清理，有引用时延迟到最后释放。 */
    public synchronized void retire(Long generationId) {
        Entry entry = entries.get(generationId);
        if (entry == null) {
            deleteSafely(generationId, directory(generationId));
            return;
        }
        entry.retired = true;
        if (entry.references == 0) {
            closeAndDelete(generationId, entry);
        }
    }

    private synchronized void release(Long generationId, Entry expected) {
        Entry current = entries.get(generationId);
        if (current != expected || current.references <= 0) {
            return;
        }
        current.references--;
        if (current.retired && current.references == 0) {
            closeAndDelete(generationId, current);
        }
    }

    private Entry open(Long generationId) {
        try {
            FSDirectory directory = FSDirectory.open(directory(generationId));
            try {
                return new Entry(directory, DirectoryReader.open(directory));
            } catch (IOException failure) {
                directory.close();
                throw failure;
            }
        } catch (IOException failure) {
            throw new CodeGenerationPublishException(failure);
        }
    }

    private void closeAndDelete(Long generationId, Entry entry) {
        entries.remove(generationId);
        try {
            entry.reader.close();
            entry.directory.close();
        } catch (IOException closeFailure) {
            LOGGER.warn("code_index_reader_close_failed generationId={} category=io", generationId);
        }
        deleteSafely(generationId, directory(generationId));
    }

    private void deleteSafely(Long generationId, Path directory) {
        try {
            cleaner.delete(directory);
        } catch (IOException failure) {
            // 清理失败只留下不可查询退休目录，不能反向破坏活动查询或请求完成。
            LOGGER.warn("code_index_retired_cleanup_failed generationId={} category=io", generationId);
        }
    }

    private Path directory(Long generationId) {
        Path directory = indexRoot.resolve(generationId.toString()).normalize();
        if (!indexRoot.equals(directory.getParent())) {
            throw new IllegalArgumentException("generation escaped index root");
        }
        return directory;
    }

    private static final class Entry {
        private final FSDirectory directory;
        private final DirectoryReader reader;
        private int references;
        private boolean retired;

        private Entry(FSDirectory directory, DirectoryReader reader) {
            this.directory = directory;
            this.reader = reader;
        }
    }
}
