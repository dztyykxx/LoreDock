package io.github.loredock.code.application;

import java.util.List;

/** 在调用方已固定的活动 generation 内执行服务端构造查询的索引端口。 */
public interface CodeIndexSearchPort {
    /** 返回稳定按 score 降序、path 升序排列的有限命中。 */
    List<CodeIndexSearchHit> search(
            ActiveCodeSnapshotDescriptor scope,
            String query,
            CodeSearchTarget target,
            String pathPrefix,
            int limit
    );
}
