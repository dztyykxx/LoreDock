package io.github.loredock.platform.web.status;

import io.github.loredock.platform.web.PlatformConfiguration;
import io.github.loredock.platform.web.SensitiveDataRedactor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemStatusController.class)
@Import({PlatformConfiguration.class, SensitiveDataRedactor.class})
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 业务目的：公开状态接口只返回运行判断所需字段，防止后续误把数据库连接串或存储路径暴露给未认证客户端。
     */
    @Test
    void systemStatusReturnsOnlyPublicFields() throws Exception {
        mockMvc.perform(get("/api/v1/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("loredock"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.databaseUrl").doesNotExist())
                .andExpect(jsonPath("$.storageRoot").doesNotExist());
    }
}
