package io.github.loredock.code.infrastructure.web;

import java.util.List;

/** 零基管理分页响应。 */
public record CodeSnapshotAdminPageResponse(
        List<CodeSnapshotAdminResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
