package io.github.loredock.project.domain;

/**
 * 保留大小写的项目分支名。分支名是业务标签，后续基础设施不得将其直接拼接为文件路径。
 *
 * @param value 已校验并去除首尾空白的分支名
 */
public record BranchName(String value) {

    /**
     * @param candidate 外部输入
     * @return 规范化后的分支名
     * @throws IllegalArgumentException 输入包含危险路径样式或超出长度边界
     */
    public static BranchName of(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        if (isInvalid(normalized)) {
            throw new IllegalArgumentException("branch name invalid");
        }
        return new BranchName(normalized);
    }

    public BranchName {
        if (isInvalid(value)) {
            throw new IllegalArgumentException("branch name invalid");
        }
    }

    private static boolean isInvalid(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.startsWith("/") || value.endsWith("/")
                || value.contains("//") || value.indexOf('\\') >= 0) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        for (String segment : value.split("/", -1)) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
