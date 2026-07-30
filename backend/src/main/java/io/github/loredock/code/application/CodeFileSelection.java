package io.github.loredock.code.application;

/**
 * 单个普通 ZIP 文件的完整选择结果。忽略项正文始终为 null，允许项保留完整文本而非截断前缀。
 *
 * @param path 规范仓库相对路径
 * @param text 完整严格 UTF-8 正文，仅允许项存在
 * @param ignoredReason 稳定忽略原因，仅忽略项存在
 */
public record CodeFileSelection(String path, String text, CodeFileIgnoreReason ignoredReason) {

    /** @return 正文可进入候选索引时为 true */
    public boolean selected() {
        return ignoredReason == null;
    }

    /** 创建完整允许文本。 */
    public static CodeFileSelection selected(String path, String text) {
        return new CodeFileSelection(path, text, null);
    }

    /** 创建不保存正文的忽略结果。 */
    public static CodeFileSelection ignored(String path, CodeFileIgnoreReason reason) {
        return new CodeFileSelection(path, null, reason);
    }
}
