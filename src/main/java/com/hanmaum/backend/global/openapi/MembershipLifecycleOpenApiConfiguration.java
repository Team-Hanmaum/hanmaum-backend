package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.carespace.code.CareSpaceErrorCode;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class MembershipLifecycleOpenApiConfiguration {
  @Bean
  OpenApiCustomizer membershipLifecycleContract() {
    return api -> {
      conflicts(
          api,
          "OWNERSHIP_TRANSFER_CONFLICT",
          List.of(
              CareSpaceErrorCode.SPACE_LABEL_DUPLICATED,
              CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT));
      conflicts(
          api, "SPACE_DEPARTURE_CONFLICT", List.of(CareSpaceErrorCode.OWNER_TRANSFER_REQUIRED));
      conflicts(
          api,
          "MEMBERSHIP_REMOVAL_CONFLICT",
          List.of(CareSpaceErrorCode.MEMBERSHIP_STATE_CONFLICT));
      for (String suffix :
          List.of("/ownership-transfers", "/departures", "/memberships/{membershipId}/removals")) {
        var path = api.getPaths().get("/api/spaces/{spaceId}" + suffix);
        if (path != null && path.getPost() != null)
          path.getPost()
              .setSecurity(
                  List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      }
    };
  }

  private static void conflicts(OpenAPI api, String name, List<ErrorCode> domainCodes) {
    List<ErrorCode> codes =
        new ArrayList<>(
            List.of(
                CommonErrorCode.VERSION_CONFLICT,
                CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                CommonErrorCode.REQUEST_IN_PROGRESS));
    codes.addAll(domainCodes);
    var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
    for (var code : codes) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("success", false);
      body.put("code", code.code());
      body.put("message", code.message());
      body.put("data", null);
      body.put("errors", List.of());
      body.put("timestamp", "2026-10-11T00:00:00Z");
      media.addExamples(code.code(), new Example().summary(code.message()).value(body));
    }
    api.getComponents()
        .addResponses(
            name,
            new ApiResponse()
                .description("버전 · 요청 ID · 현재 참여/소유권 상태 충돌")
                .content(new Content().addMediaType("application/json", media)));
  }
}
