package io.github.loredock.code.model.result;

import java.time.Instant;

/** 单个代码命中及其固定活动版本来源。 */
public record CodeSearchResult(
        String projectIdentifier,
        String branch,
        Long snapshotId,
        String commit,
        Instant indexedAt,
        String path,
        String snippet,
        float score,
        boolean truncated
) {
}
