package io.github.loredock.code.application;

import java.time.Instant;
import java.util.UUID;

/** 单个代码命中及其固定活动版本来源。 */
public record CodeSearchResult(
        String projectIdentifier,
        String branch,
        UUID snapshotId,
        String commit,
        Instant indexedAt,
        String path,
        String snippet,
        float score,
        boolean truncated
) {
}
