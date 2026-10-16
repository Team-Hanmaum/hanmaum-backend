package com.hanmaum.backend.ai.client.v2;

import com.hanmaum.backend.ai.client.AiProperties;
import com.hanmaum.backend.ai.code.AiFailureCode;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest;
import com.hanmaum.backend.ai.dto.v2.AiV2AnalysisResponse;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Prepared adapter only. AnalysisProcessor continues to call v1 until execution support lands. */
@Component
public class HanmaumAiV2Client {
  private final RestClient client;
  private final AiV2Properties settings;
  private final long timeoutNanos;
  private final AiV2ErrorMapper errors = new AiV2ErrorMapper();

  public HanmaumAiV2Client(
      @Qualifier("hanmaumAiV2RestClient") RestClient client,
      AiV2Properties settings,
      AiProperties ai) {
    this.client = client;
    this.settings = settings;
    this.timeoutNanos = ai.readTimeout().toNanos();
  }

  // Call outside a DB transaction. No retry, v1 fallback, receipt store or result cache.
  public AiV2AnalysisResponse analyze(AiV2AnalysisRequest request) {
    var limits = settings.requireLimits();
    var contract = new AiV2Contract(limits);
    byte[] body = contract.encodeRequest(request);
    long started = System.nanoTime();
    try {
      return client
          .post()
          .uri("/v2/analyses")
          .contentType(MediaType.APPLICATION_JSON)
          .header("X-Request-Id", request.requestId().toString())
          .body(body)
          .exchange(
              (sent, response) -> {
                int status = response.getStatusCode().value();
                byte[] raw;
                try {
                  raw =
                      AiV2PayloadLimits.read(
                          response.getBody(),
                          response.getHeaders().getContentLength(),
                          limits.responseBytes());
                } catch (IllegalArgumentException exception) {
                  throw new AiV2Exception(AiFailureCode.AI_INVALID_RESPONSE);
                }
                if (System.nanoTime() - started >= timeoutNanos)
                  throw new AiV2Exception(AiFailureCode.AI_TIMEOUT);
                if (status != 200)
                  throw new AiV2Exception(errors.map(status, raw, limits.responseBytes()));
                return contract.parseResponse(request, raw);
              });
    } catch (ResourceAccessException exception) {
      // A deadline during streaming closes the JDK input stream with a plain IOException.
      if (System.nanoTime() - started >= timeoutNanos)
        throw new AiV2Exception(AiFailureCode.AI_TIMEOUT);
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
          throw new AiV2Exception(AiFailureCode.AI_TIMEOUT);
        }
      }
      throw new AiV2Exception(AiFailureCode.AI_SERVICE_UNAVAILABLE);
    } catch (RestClientException exception) {
      throw new AiV2Exception(AiFailureCode.AI_SERVICE_UNAVAILABLE);
    }
  }
}
