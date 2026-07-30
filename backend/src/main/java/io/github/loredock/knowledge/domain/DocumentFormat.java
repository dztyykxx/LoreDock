package io.github.loredock.knowledge.domain;

/** 知识正文的保存格式；两种格式都只保存文本，不代表服务端会执行或渲染内容。 */
public enum DocumentFormat {
    MARKDOWN,
    PLAIN_TEXT
}
