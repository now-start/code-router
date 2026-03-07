package org.nowstart.router.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.nowstart.coderouter.config.CodeRouterProperties;
import org.nowstart.router.infrastructure.codex.CodexAppServerClient;
import org.springframework.core.io.ClassPathResource;

class CodexChatServiceTest {

    @Test
    void completesChatAgainstFakeAppServer() throws Exception {
        CodeRouterProperties properties = properties();
        try (CodexAppServerClient client = new CodexAppServerClient(new ObjectMapper(), properties)) {
            CodexAccountService accountService = new CodexAccountService(client);
            accountService.subscribeToNotifications();
            CodexChatService chatService = new CodexChatService(client, accountService, properties);

            ChatExecutionResult result = chatService.complete("User: Say hello.");

            assertThat(result.content()).isEqualTo("Hello from fake Codex.");
            assertThat(result.model()).isEqualTo("codex");
        }
    }

    private CodeRouterProperties properties() throws Exception {
        CodeRouterProperties properties = new CodeRouterProperties();
        properties.getCodex().setCommand("bash");
        properties.getCodex().setArguments(List.of(scriptPath().toString(), "happy-path"));
        properties.getCodex().setWorkdir(scriptPath().getParent());
        properties.getCodex().setRequestTimeout(Duration.ofSeconds(5));
        return properties;
    }

    private Path scriptPath() throws Exception {
        return new ClassPathResource("fake-codex/fake-codex-app-server.sh").getFile().toPath();
    }
}
