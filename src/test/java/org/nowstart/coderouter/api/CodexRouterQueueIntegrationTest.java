package org.nowstart.coderouter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
class CodexRouterQueueIntegrationTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        CodexTestSupport.register(registry, "happy-path", 1);
    }

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    void rejectsThirdRequestWhenQueueCapacityIsExhausted() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            CompletableFuture<Integer> first = invokeAsync(executor, "LONG_RUNNING_FIRST");
            Thread.sleep(100L);
            CompletableFuture<Integer> second = invokeAsync(executor, "LONG_RUNNING_SECOND");
            Thread.sleep(100L);
            CompletableFuture<Integer> third = invokeAsync(executor, "LONG_RUNNING_THIRD");

            assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(429);
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletableFuture<Integer> invokeAsync(ExecutorService executor, String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(post("/v1/chat/completions")
                                .contentType(APPLICATION_JSON)
                                .content("""
                                        {
                                          "model": "codex",
                                          "messages": [
                                            {"role": "user", "content": "%s"}
                                          ],
                                          "stream": false
                                        }
                                        """.formatted(prompt)))
                        .andReturn()
                        .getResponse()
                        .getStatus();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }, executor);
    }
}
