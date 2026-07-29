package io.github.loredock.project.domain;

/**
 * 项目生命周期状态；停用只控制普通查询可见性，不代表删除项目或分支。
 */
public enum ProjectStatus {
    /** 普通与管理入口均可查询。 */
    ENABLED,
    /** 仅管理入口可查询，持久化数据保持不变。 */
    DISABLED
}
