package org.nowstart.coderouter.api;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nowstart.coderouter.support.CodexTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class CodexRouterLoggedOutIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        CodexTestSupport.register(registry, "logged-out", 8);
    }

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void reportsLoggedOutAdminStatus() throws Exception {
        mockMvc.perform(get("/admin/codex/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logged_in").value(false))
                .andExpect(jsonPath("$.requires_openai_auth").value(true));
    }

    @Test
    void rejectsChatRequestsWhenCodexAccountIsMissing() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "codex",
                                  "messages": [
                                    {"role": "user", "content": "Hello"}
                                  ]
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("codex_not_logged_in"));
    }
}
