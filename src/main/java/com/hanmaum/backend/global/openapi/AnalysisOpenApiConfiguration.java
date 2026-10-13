package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.analysis.code.AnalysisErrorCode;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class AnalysisOpenApiConfiguration {
  @Bean
  OpenApiCustomizer analysisContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (ErrorCode code :
          List.of(
              CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
              CommonErrorCode.REQUEST_IN_PROGRESS,
              CommonErrorCode.VERSION_CONFLICT,
              AnalysisErrorCode.ANALYSIS_STATE_CONFLICT)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put("errors", List.of());
        body.put("timestamp", "2026-10-12T00:00:00Z");
        media.addExamples(code.code(), new Example().summary(code.message()).value(body));
      }
      api.getComponents()
          .addResponses(
              "RETRY_ANALYSIS_CONFLICT",
              new ApiResponse()
                  .description("요청 ID 재사용·처리 중·원본 버전 충돌·최신 실패 실행 아님")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces/{spaceId}/source-records/{recordId}/analyses");
      if (path != null && path.getPost() != null)
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
    };
  }
}
