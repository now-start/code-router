package org.nowstart.coderouter.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class CodexTestSupport {

    private CodexTestSupport() {
    }

    public static void register(DynamicPropertyRegistry registry, String scenario, int queueCapacity) {
        registry.add("coderouter.codex.command", () -> "bash");
        registry.add("coderouter.codex.arguments[0]", CodexTestSupport::scriptPath);
        registry.add("coderouter.codex.arguments[1]", () -> scenario);
        registry.add("coderouter.codex.workdir", CodexTestSupport::workdirPath);
        registry.add("coderouter.queue.capacity", () -> Integer.toString(queueCapacity));
        registry.add("coderouter.queue.max-wait", () -> "PT30S");
        registry.add("spring.mvc.async.request-timeout", () -> "5000");
    }

    private static String scriptPath() {
        try {
            return new ClassPathResource("fake-codex/fake-codex-app-server.sh").getFile().getAbsolutePath();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve fake Codex app server script", exception);
        }
    }

    private static String workdirPath() {
        try {
            Path resourceRoot = Paths.get(new ClassPathResource("fake-codex/fake-codex-app-server.sh").getURI()).getParent();
            return resourceRoot.toAbsolutePath().toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to resolve fake Codex workdir", exception);
        }
    }
}
