package com.hanmaum.backend.ai.client.v2;

import com.hanmaum.backend.ai.client.AiProperties;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AiV2ClientConfig {
  @Bean(destroyMethod = "shutdownNow")
  HttpClient hanmaumAiV2HttpClient(AiProperties properties) {
    return HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  @Bean
  RestClient hanmaumAiV2RestClient(
      RestClient.Builder builder,
      AiProperties properties,
      @Qualifier("hanmaumAiV2HttpClient") HttpClient httpClient) {
    var factory = new JdkClientHttpRequestFactory(httpClient);
    // Spring's JDK transport cancels the exchange/closes the body at this overall deadline.
    factory.setReadTimeout(properties.readTimeout());
    factory.enableCompression(false);
    return builder
        .baseUrl(properties.baseUrl().toString())
        .defaultHeader("X-Internal-Api-Key", properties.apiKey())
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity")
        .requestFactory(factory)
        .build();
  }
}
