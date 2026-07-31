package io.github.loredock.project.service.demo;

import io.github.loredock.project.model.result.DemoPreparationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 仅在演示/测试 profile 与显式开关同时开启时自动运行准备器，生产默认不会创建任何样例数据。
 */
@Component
@Profile({"demo", "test"})
@ConditionalOnProperty(prefix = "loredock.demo", name = "seed-enabled", havingValue = "true")
public class DemoProjectDataRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoProjectDataRunner.class);
    private final DemoProjectDataPreparer preparer;

    /** @param preparer 幂等演示数据准备器 */
    public DemoProjectDataRunner(DemoProjectDataPreparer preparer) {
        this.preparer = preparer;
    }

    @Override
    public void run(ApplicationArguments args) {
        DemoPreparationReport report = preparer.prepare();
        LOGGER.info(
                "demo_project_preparation createdProjects={} reusedProjects={} createdBranches={} reusedBranches={}",
                report.createdProjects(), report.reusedProjects(), report.createdBranches(), report.reusedBranches());
    }
}
