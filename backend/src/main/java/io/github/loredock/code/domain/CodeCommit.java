package io.github.loredock.code.domain;

import java.util.Locale;

/**
 * 管理员声明的 Git 对象标识。该值只验证格式并规范为小写，不声称已通过远程 Git 平台验证。
 *
 * @param value 7～64 位小写十六进制
 */
public record CodeCommit(String value) {

    /** 创建用于展示和范围固定的规范 commit 声明。 */
    public CodeCommit {
        if (value == null) {
            throw new IllegalArgumentException("commit 不能为空");
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("commit 必须为 7 到 64 位十六进制");
        }
    }
}
