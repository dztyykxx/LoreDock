package io.github.loredock.code.model.enums;

/** 代码文件未进入索引的稳定计数原因；不得附带被忽略正文。 */
public enum CodeFileIgnoreReason {
    EXCLUDED_PATH,
    SENSITIVE_PATH,
    BINARY_FILE_TYPE,
    FILE_TOO_LARGE,
    BINARY_CONTENT,
    INVALID_UTF8
}
