package io.github.loredock.code.service.index;

import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;

/** 单次查询固定持有的 Lucene reader 引用；关闭幂等。 */
public final class LuceneIndexHandle implements AutoCloseable {

    private final DirectoryReader reader;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    LuceneIndexHandle(DirectoryReader reader, Runnable release) {
        this.reader = reader;
        this.release = release;
    }

    /** @return 本次请求固定 generation 的只读 reader。 */
    public DirectoryReader reader() {
        if (closed.get()) {
            throw new IllegalStateException("lucene index handle is closed");
        }
        return reader;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
