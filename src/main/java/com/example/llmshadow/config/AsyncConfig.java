package com.example.llmshadow.config;

import com.example.llmshadow.config.properties.ShadowProperties;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "shadowTaskExecutor")
    public ThreadPoolTaskExecutor shadowTaskExecutor(ShadowProperties shadowProperties) {
        ShadowProperties.Executor properties = shadowProperties.executor();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("shadow-");
        executor.setTaskDecorator(this::withMdcContext);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.awaitTerminationSeconds());
        executor.initialize();
        return executor;
    }

    private Runnable withMdcContext(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousContextMap = MDC.getCopyOfContextMap();
            try {
                if (contextMap == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                if (previousContextMap == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previousContextMap);
                }
            }
        };
    }
}
