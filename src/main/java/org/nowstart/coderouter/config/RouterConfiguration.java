package org.nowstart.coderouter.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouterConfiguration {

    @Bean(destroyMethod = "close")
    ExecutorService chatStreamingExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
