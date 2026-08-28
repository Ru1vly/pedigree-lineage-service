package com.edevlet.lineage.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ActuatorMetricsConfig {

    @Bean
    public Counter submittedQueriesCounter(MeterRegistry registry) {
        return Counter.builder("lineage.queries.submitted")
                .description("Total number of pedigree lineage queries submitted")
                .tag("service", "pedigree-lineage-service")
                .register(registry);
    }

    @Bean
    public Counter completedQueriesCounter(MeterRegistry registry) {
        return Counter.builder("lineage.queries.completed")
                .description("Total number of successfully completed lineage queries")
                .tag("service", "pedigree-lineage-service")
                .register(registry);
    }

    @Bean
    public Counter failedQueriesCounter(MeterRegistry registry) {
        return Counter.builder("lineage.queries.failed")
                .description("Total number of failed lineage queries")
                .tag("service", "pedigree-lineage-service")
                .register(registry);
    }
}
