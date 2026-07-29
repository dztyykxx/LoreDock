package io.github.loredock.platform.web;

/**
 * 带稳定错误语义的应用失败；详细上下文只用于受控日志，不直接作为 HTTP 消息。
 */
public class ApplicationException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 创建具有稳定错误码的应用异常。
     *
     * @param errorCode 对外错误语义
     * @param diagnosticMessage 仅供服务端诊断的上下文
     */
    public ApplicationException(ErrorCode errorCode, String diagnosticMessage) {
        super(diagnosticMessage);
        this.errorCode = errorCode;
    }

    /**
     * 创建保留原始失败链的应用异常，供服务端诊断和统一脱敏处理。
     *
     * @param errorCode 对外错误语义
     * @param diagnosticMessage 仅供服务端诊断的上下文
     * @param cause 原始失败
     */
    public ApplicationException(ErrorCode errorCode, String diagnosticMessage, Throwable cause) {
        super(diagnosticMessage, cause);
        this.errorCode = errorCode;
    }

    /**
     * @return 稳定错误码
     */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
