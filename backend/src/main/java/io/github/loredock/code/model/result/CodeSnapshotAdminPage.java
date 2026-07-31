package io.github.loredock.code.model.result;

import java.util.List;

/** 按 {@code createdAt DESC, id ASC} 稳定排序的零基管理分页。 */
public record CodeSnapshotAdminPage(
        List<CodeSnapshotAdminView> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
