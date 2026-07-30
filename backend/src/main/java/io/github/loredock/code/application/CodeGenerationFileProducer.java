package io.github.loredock.code.application;

/** 在 writer 生命周期内逐文件生产已完成安全选择的代码正文。 */
@FunctionalInterface
public interface CodeGenerationFileProducer {

    /**
     * 生产者不得跨回调保留文件正文；消费完成后即可释放当前单文件内存。
     *
     * @param consumer generation writer 提供的逐文件消费者
     */
    void produce(CodeGenerationFileConsumer consumer);
}
