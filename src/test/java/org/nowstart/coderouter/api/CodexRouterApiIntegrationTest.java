package org.nowstart.coderouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nowstart.coderouter.support.CodexTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class CodexRouterApiIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        CodexTestSupport.register(registry, "happy-path", 8);
    }

    @BeforeEach
    void setUpMockMvc() throws Exception {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        ensureLoggedIn();
    }

    @Test
    void returnsSingleCodexModel() throws Exception {
        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("codex"))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void returnsChatCompletionForNonStreamRequest() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "codex",
                                  "messages": [
                                    {"role": "system", "content": "Reply briefly."},
                                    {"role": "user", "content": "Say hello."}
                                  ],
                                  "stream": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.model").value("codex"))
                .andExpect(jsonPath("$.choices[0].message.role").value("assistant"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello from fake Codex."))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"));
    }

    @Test
    void streamsChatCompletionChunks() throws Exception {
        MvcResult pending = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "codex",
                                  "messages": [
                                    {"role": "user", "content": "STREAM_VARIANT"}
                                  ],
                                  "stream": true
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn();

        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"object\":\"chat.completion.chunk\"")
                .contains("Streaming reply ")
                .contains("from fake Codex.")
                .contains("[DONE]");
    }

    @Test
    void rejectsUnsupportedToolsField() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "codex",
                                  "messages": [
                                    {"role": "user", "content": "Hello"}
                                  ],
                                  "tools": [
                                    {"type": "function", "function": {"name": "bad"}}
                                  ]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("unsupported_feature"));
    }

    @Test
    void exposesAdminAccountStatus() throws Exception {
        mockMvc.perform(get("/admin/codex/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logged_in").value(true))
                .andExpect(jsonPath("$.account.email").value("codex-router@example.test"))
                .andExpect(jsonPath("$.account.plan_type").value("pro"));
    }

    @Test
    void supportsAdminLoginStartPollCancelAndLogout() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/admin/codex/account/login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.auth_url").value(org.hamcrest.Matchers.startsWith("https://example.test/device/")))
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String loginId = loginJson.get("login_id").asText();

        JsonNode poll = pollLoginStatus(loginId, "completed");
        assertThat(poll.path("status").asText()).isEqualTo("completed");

        MvcResult cancelResult = mockMvc.perform(post("/admin/codex/account/login"))
                .andExpect(status().isOk())
                .andReturn();
        String cancelLoginId = objectMapper.readTree(cancelResult.getResponse().getContentAsString()).get("login_id").asText();

        mockMvc.perform(delete("/admin/codex/account/login/{loginId}", cancelLoginId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        mockMvc.perform(get("/admin/codex/account/login/{loginId}", cancelLoginId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        mockMvc.perform(delete("/admin/codex/account/session"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/codex/account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logged_in").value(false));
    }

    private JsonNode pollLoginStatus(String loginId, String expectedStatus) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        JsonNode lastResponse = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult result = mockMvc.perform(get("/admin/codex/account/login/{loginId}", loginId))
                    .andExpect(status().isOk())
                    .andReturn();
            lastResponse = objectMapper.readTree(result.getResponse().getContentAsString());
            if (expectedStatus.equals(lastResponse.path("status").asText())) {
                return lastResponse;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Expected login status '" + expectedStatus + "' but last response was " + lastResponse);
    }

    private void ensureLoggedIn() throws Exception {
        MvcResult accountResult = mockMvc.perform(get("/admin/codex/account"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode accountJson = objectMapper.readTree(accountResult.getResponse().getContentAsString());
        if (accountJson.path("logged_in").asBoolean(false)) {
            return;
        }

        MvcResult loginResult = mockMvc.perform(post("/admin/codex/account/login"))
                .andExpect(status().isOk())
                .andReturn();

        String loginId = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("login_id").asText();
        pollLoginStatus(loginId, "completed");
    }
}
