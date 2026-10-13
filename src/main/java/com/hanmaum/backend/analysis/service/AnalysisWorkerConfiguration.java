package com.hanmaum.backend.analysis.service;

import com.hanmaum.backend.ai.client.AiProperties;
import com.hanmaum.backend.analysis.repository.AnalysisRepository;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalysisWorkerConfiguration.Settings.class)
public class AnalysisWorkerConfiguration {
  @ConfigurationProperties("app.analysis.worker")
  public record Settings(
      boolean enabled, Integer concurrency, Duration pollInterval, Duration leaseDuration) {
    public void validate(AiProperties ai) {
      if (concurrency == null
          || concurrency < 1
          || pollInterval == null
          || pollInterval.toMillis() < 1
          || leaseDuration == null
          || leaseDuration.compareTo(ai.connectTimeout().plus(ai.readTimeout())) <= 0)
        throw new IllegalArgumentException(
            "Enabled analysis worker requires explicit positive concurrency/poll-interval and lease-duration longer than AI timeouts");
    }
  }

  @Bean(initMethod = "start", destroyMethod = "close")
  @ConditionalOnProperty(name = "app.analysis.worker.enabled", havingValue = "true")
  AnalysisWorker analysisWorker(
      Settings settings,
      AiProperties ai,
      AnalysisRepository runs,
      AnalysisExecutionService execution,
      AnalysisProcessor processor) {
    settings.validate(ai);
    return new AnalysisWorker(settings, runs, execution, processor);
  }
}
