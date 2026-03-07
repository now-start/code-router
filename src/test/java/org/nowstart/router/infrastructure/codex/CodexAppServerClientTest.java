package org.nowstart.router.infrastructure.codex;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.springframework.core.io.ClassPathResource;

class CodexAppServerClientTest {

    @Test
    void canTalkToFakeCodexAppServer() throws Exception {
        CodeRouterProperties properties = new CodeRouterProperties();
        properties.getCodex().setCommand("bash");
        properties.getCodex().setArguments(java.util.List.of(
                scriptPath().toString(),
                "happy-path"
        ));
        properties.getCodex().setWorkdir(scriptPath().getParent());

        CompletableFuture<String> delta = new CompletableFuture<>();
        try (CodexAppServerClient client = new CodexAppServerClient(new ObjectMapper(), properties)) {
            JsonNode account = client.request("account/read", java.util.Map.of());
            assertThat(account.path("account").path("email").asText()).isEqualTo("codex-router@example.test");

            CompletableFuture<String> completedTurn = new CompletableFuture<>();
            client.addNotificationListener((method, params) -> {
                if ("agentMessageDelta".equals(method)) {
                    delta.complete(params.path("delta").asText());
                }
                if ("turn/completed".equals(method)) {
                    completedTurn.complete(params.path("turn").path("status").asText());
                }
            });

            JsonNode thread = client.request("thread/start", java.util.Map.of("cwd", properties.getCodex().getWorkdir().toString()));
            String threadId = thread.path("thread").path("id").asText();
            JsonNode turn = client.request("turn/start", java.util.Map.of(
                    "threadId", threadId,
                    "input", java.util.List.of(java.util.Map.of("type", "text", "text", "Say hello"))
            ));

            assertThat(turn.path("turn").path("id").asText()).startsWith("turn-");
            assertThat(delta.get(2, TimeUnit.SECONDS)).isNotBlank();
            assertThat(completedTurn.get(2, TimeUnit.SECONDS)).isEqualTo("completed");
        }
    }

    private Path scriptPath() throws Exception {
        return new ClassPathResource("fake-codex/fake-codex-app-server.sh").getFile().toPath();
    }
}
