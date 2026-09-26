package com.hanmaum.backend.ai.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AiClientConfig {
  @Bean
  RestClient hanmaumAiRestClient(RestClient.Builder builder, AiProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.connectTimeout());
    factory.setReadTimeout(properties.readTimeout());
    return builder
        .baseUrl(properties.baseUrl().toString())
        .defaultHeader("X-Internal-Api-Key", properties.apiKey())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .requestFactory(factory)
        .build();
  }
}
