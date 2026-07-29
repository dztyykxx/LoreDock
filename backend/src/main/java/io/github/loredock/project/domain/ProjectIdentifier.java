package io.github.loredock.project.domain;

import java.util.regex.Pattern;

/**
 * 项目对检索与 MCP 暴露的稳定业务标识，使用 2 至 64 位小写 kebab-case。
 *
 * @param value 已校验并去除首尾空白的标识
 */
public record ProjectIdentifier(String value) {

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

    /**
     * @param candidate 外部输入
     * @return 规范化后的项目标识
     * @throws IllegalArgumentException 输入不满足稳定业务键规则
     */
    public static ProjectIdentifier of(String candidate) {
        String normalized = candidate == null ? "" : candidate.strip();
        if (normalized.length() < 2 || normalized.length() > 64 || !KEBAB_CASE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("project identifier invalid");
        }
        return new ProjectIdentifier(normalized);
    }

    public ProjectIdentifier {
        if (value == null || value.length() < 2 || value.length() > 64 || !KEBAB_CASE.matcher(value).matches()) {
            throw new IllegalArgumentException("project identifier invalid");
        }
    }
}
