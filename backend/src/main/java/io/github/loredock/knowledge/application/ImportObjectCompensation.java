package io.github.loredock.knowledge.application;

/** 对象已成功写入但批次无法建立时的幂等补偿边界。 */
public interface ImportObjectCompensation {

    /**
     * 删除尚未被导入批次引用的对象；重复调用必须成功，日志不得包含对象键、文件正文或内部路径。
     *
     * @param objectKey 仅供内部补偿定位的对象键
     */
    void deleteUnreferenced(String objectKey);
}
