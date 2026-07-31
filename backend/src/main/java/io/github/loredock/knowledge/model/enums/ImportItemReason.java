package io.github.loredock.knowledge.model.enums;

/** 可持久化并安全返回给用户的导入条目原因码。 */
public enum ImportItemReason {
    IMPORTED,
    UNSUPPORTED_FILE_TYPE,
    INVALID_TEXT_ENCODING,
    UNSAFE_ENTRY_PATH,
    UNSUPPORTED_ENTRY_TYPE,
    INVALID_DOCUMENT_FIELDS,
    DOCUMENT_SCOPE_INVALID,
    DOCUMENT_PERSISTENCE_FAILED,
    NO_IMPORTABLE_DOCUMENTS
}
