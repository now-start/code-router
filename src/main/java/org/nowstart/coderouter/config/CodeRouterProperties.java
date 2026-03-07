package org.nowstart.coderouter.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "coderouter")
public class CodeRouterProperties {

    private String modelAlias = "codex";
    private Codex codex = new Codex();
    private Queue queue = new Queue();

    @Getter
    @Setter
    public static class Codex {

        private String command = "codex";
        private List<String> arguments = new ArrayList<>();
        private Path processWorkingDirectory = Path.of(".");
        private Path workdir = Path.of(".");
        private String model = "codex";
        private Duration startupTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofMinutes(5);
        private Map<String, Object> sandboxPolicy = new LinkedHashMap<>();
        private String approvalPolicy = "never";
        private boolean ephemeral = true;
        private String personality = "none";
        private String baseInstructions = "";
        private String developerInstructions = "";
    }

    @Getter
    @Setter
    public static class Queue {

        private int capacity = 8;
        private Duration maxWait = Duration.ofSeconds(30);
    }
}
