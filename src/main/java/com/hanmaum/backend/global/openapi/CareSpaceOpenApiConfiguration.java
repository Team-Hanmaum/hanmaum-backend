package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
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
public class CareSpaceOpenApiConfiguration {
  @Bean
  OpenApiCustomizer careSpaceCreationContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (ErrorCode code :
          List.of(
              CareSpaceErrorCode.SPACE_LABEL_DUPLICATED,
              CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
              CommonErrorCode.REQUEST_IN_PROGRESS)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put(
            "errors",
            code == CareSpaceErrorCode.SPACE_LABEL_DUPLICATED
                ? List.of(Map.of("field", "subjectLabel", "reason", "이미 소유한 공간과 다른 호칭을 입력해주세요."))
                : List.of());
        body.put("timestamp", "2026-10-10T00:00:00Z");
        media.addExamples(code.code(), new Example().summary(code.message()).value(body));
      }
      api.getComponents()
          .addResponses(
              "CREATE_CARE_SPACE_CONFLICT",
              new ApiResponse()
                  .description("호칭 중복 · 요청 ID 재사용 · 동일 요청 처리 중")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces");
      if (path != null && path.getPost() != null) {
        // One requirement object means session AND CSRF, not two alternative auth methods.
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      }
    };
  }
}
