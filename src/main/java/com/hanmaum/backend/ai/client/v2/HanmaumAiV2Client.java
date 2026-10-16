package com.hanmaum.backend.ai.client.v2;

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
  private final AiV2Contract contract = new AiV2Contract();
  private final AiV2ErrorMapper errors = new AiV2ErrorMapper();

  public HanmaumAiV2Client(@Qualifier("hanmaumAiRestClient") RestClient client) {
    this.client = client;
  }

  // Call outside a DB transaction. No retry, v1 fallback, receipt store or result cache.
  public AiV2AnalysisResponse analyze(AiV2AnalysisRequest request) {
    byte[] body = contract.encodeRequest(request);
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
                byte[] raw = response.getBody().readAllBytes();
                if (status != 200) throw new AiV2Exception(errors.map(status, raw));
                return contract.parseResponse(request, raw);
              });
    } catch (ResourceAccessException exception) {
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
