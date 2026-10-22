package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.careitem.code.CareItemErrorCode;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.*;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class ProposalDecisionOpenApiConfiguration {
  @Bean
  OpenApiCustomizer proposalDecisionContract() {
    return api -> {
      String base = "/api/spaces/{spaceId}";
      var conflicts =
          Map.<String, List<ErrorCode>>of(
              "/proposal-applications",
                  List.of(
                      ProposalErrorCode.PROPOSAL_STATE_CONFLICT,
                      ProposalErrorCode.PROPOSAL_BATCH_CONFLICT,
                      CommonErrorCode.VERSION_CONFLICT,
                      CareItemErrorCode.ITEM_LOCKED,
                      CareItemErrorCode.EDIT_LOCK_EXPIRED,
                      CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                      CommonErrorCode.REQUEST_IN_PROGRESS),
              "/proposal-declines",
                  List.of(
                      ProposalErrorCode.PROPOSAL_STATE_CONFLICT,
                      CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                      CommonErrorCode.REQUEST_IN_PROGRESS),
              "/items/{itemId}/edit-lock",
                  List.of(CommonErrorCode.VERSION_CONFLICT, CareItemErrorCode.ITEM_LOCKED),
              "/items/{itemId}/edit-lock/renewals", List.of(CareItemErrorCode.EDIT_LOCK_EXPIRED),
              "/items/{itemId}/edit-lock/releases", List.of(CareItemErrorCode.EDIT_LOCK_EXPIRED));
      conflicts.forEach(
          (suffix, codes) -> {
            var path = api.getPaths().get(base + suffix);
            if (path == null || path.getPost() == null) return;
            var op = path.getPost();
            op.setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
            op.getRequestBody().setRequired(true);
            var media =
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
            for (var code : codes) {
              Map<String, Object> example = new LinkedHashMap<>();
              example.put("success", false);
              example.put("code", code.code());
              example.put("message", code.message());
              example.put("data", null);
              example.put("errors", List.of());
              example.put("timestamp", "2026-10-22T08:00:00Z");
              media.addExamples(code.code(), new Example().value(example));
            }
            op.getResponses()
                .addApiResponse(
                    "409",
                    new ApiResponse()
                        .description("현재 상태·동시 변경 충돌")
                        .content(new Content().addMediaType("application/json", media)));
            if (suffix.endsWith("/releases")) op.getResponses().get("204").setContent(null);
          });
      Map.of(
              "ApplicationBody",
              List.of("clientRequestId", "entries"),
              "DeclineBody",
              List.of("clientRequestId", "entries"),
              "ApplicationEntry",
              List.of("proposalId", "action", "reviewToken"),
              "DeclineEntry",
              List.of("proposalId", "reviewToken"),
              "AcquireBody",
              List.of("expectedItemVersion"),
              "TokenBody",
              List.of("leaseToken"))
          .forEach(
              (name, required) -> {
                var schema = api.getComponents().getSchemas().get(name);
                if (schema != null) {
                  schema.setRequired(required);
                  schema.setAdditionalProperties(false);
                }
              });
    };
  }
}
