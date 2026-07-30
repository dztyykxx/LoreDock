package io.github.loredock.code.application;

/** 已登录 ADMIN 与 MEMBER 共用的活动代码快照状态端口。 */
public interface ActiveCodeSnapshotQueryUseCase {

    /**
     * 只解析已启用项目；branch 为空时使用该项目默认 main，未知分支绝不回退。
     * 分支存在但没有成功快照时返回 NOT_INDEXED，候选和物理位置不可见。
     *
     * @param projectIdentifier 项目业务标识
     * @param branch 可选分支名
     * @return 当前活动快照摘要或 NOT_INDEXED
     */
    ActiveCodeSnapshotView get(String projectIdentifier, String branch);
}
