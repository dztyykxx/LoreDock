package io.github.loredock.agent.domain;

/** 回答声明使用的事实来源类型，用于服务端校验模型输出。 */
public enum AnswerBasis {
    BUSINESS_RULE,
    CURRENT_IMPLEMENTATION,
    MIXED
}
