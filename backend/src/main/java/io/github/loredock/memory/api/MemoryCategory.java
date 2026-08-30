package io.github.loredock.memory.api;

/**
 * 记忆分类：与文档产出偏好的主题域对应，写入与人工编辑时必填。
 */
public enum MemoryCategory {

    /** 格式偏好（章节组织、表格、编号方式等）。 */
    FORMAT,

    /** 模板偏好（固定文档模板、复用结构）。 */
    TEMPLATE,

    /** 内容取舍偏好（哪些内容必须保留/省略）。 */
    CONTENT,

    /** 语言风格偏好（措辞、语气、正式与否）。 */
    STYLE,

    /** 流程习惯偏好（步骤顺序、评审口径）。 */
    PROCESS,

    /** 其他偏好。 */
    OTHER
}
