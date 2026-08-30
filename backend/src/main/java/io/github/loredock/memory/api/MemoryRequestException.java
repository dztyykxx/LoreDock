package io.github.loredock.memory.api;

/**
 * 记忆读写请求被稳定业务规则拒绝，错误码对调用方（Agent 工具、HTTP、测试）稳定可判断。
 */
public class MemoryRequestException extends RuntimeException {

    private final Code code;

    /**
     * @param code 稳定失败码
     */
    public MemoryRequestException(Code code) {
        super(code.name());
        this.code = code;
    }

    /**
     * @param code 稳定失败码
     * @param detail 中文原因说明（失败日志与回复用）
     */
    public MemoryRequestException(Code code, String detail) {
        super(detail);
        this.code = code;
    }

    /** @return 调用方可稳定判断的失败码 */
    public Code code() {
        return code;
    }

    /** 记忆范围、项目、字段、预算与判断链的稳定失败语义。 */
    public enum Code {
        /** 记忆编号不存在或已被删除。 */
        MEMORY_NOT_FOUND,

        /** 记忆对请求范围不可达（非 GLOBAL ∪ 请求会话项目）；正文不返回、频次不变。 */
        MEMORY_SCOPE_VIOLATION,

        /** PROJECT 记忆绑定的项目不存在或已停用，返回明确错误且不产生记录。 */
        MEMORY_PROJECT_INVALID,

        /** 字段校验失败（枚举、长度、数量上限），不写入。 */
        MEMORY_FIELD_INVALID,

        /** 编辑接口不允许修改范围或所属项目（变更范围视为新建）。 */
        MEMORY_SCOPE_EDIT_FORBIDDEN,

        /** 写入判断链的模型调用失败或不可用，本次写入失败、可整体重试，不产生无判断记录。 */
        MEMORY_JUDGE_UNAVAILABLE,

        /** 单 run 累计新写记忆达到上限（默认 10），需人工管理后继续。 */
        MEMORY_BUDGET_EXCEEDED
    }
}
