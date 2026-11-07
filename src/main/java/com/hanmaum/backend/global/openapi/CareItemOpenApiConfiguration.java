package com.hanmaum.backend.global.openapi;

import com.hanmaum.backend.careitem.code.CareItemErrorCode;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.code.ErrorCode;
import com.hanmaum.backend.proposal.code.ProposalErrorCode;
import io.swagger.v3.oas.models.Components;
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
public class CareItemOpenApiConfiguration {
  @Bean
  OpenApiCustomizer careItemContract() {
    return api -> {
      var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
      for (var code :
          List.of(CommonErrorCode.IDEMPOTENCY_KEY_REUSED, CommonErrorCode.REQUEST_IN_PROGRESS)) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code.code());
        body.put("message", code.message());
        body.put("data", null);
        body.put("errors", List.of());
        body.put("timestamp", "2026-10-14T00:00:00Z");
        media.addExamples(code.code(), new Example().value(body));
      }
      api.getComponents()
          .addResponses(
              "CREATE_CARE_ITEM_CONFLICT",
              new ApiResponse()
                  .description("요청 ID 재사용·동일 요청 처리 중")
                  .content(new Content().addMediaType("application/json", media)));
      var path = api.getPaths().get("/api/spaces/{spaceId}/items");
      if (path != null && path.getPost() != null)
        path.getPost()
            .setSecurity(
                List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      var components = api.getComponents();
      components.addResponses(
          "RECONFIRM_CARE_ITEM_CONFLICT",
          conflict(
              components,
              "항목 상태·버전·편집 잠금·중복 요청 충돌",
              List.of(
                  CommonErrorCode.VERSION_CONFLICT,
                  CareItemErrorCode.ITEM_STATE_CONFLICT,
                  CareItemErrorCode.ITEM_LOCKED,
                  CareItemErrorCode.EDIT_LOCK_EXPIRED,
                  CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                  CommonErrorCode.REQUEST_IN_PROGRESS)));
      components.addResponses(
          "SUBMIT_QUESTION_ANSWER_CONFLICT",
          conflict(
              components,
              "질문 상태·버전·중복 요청 충돌",
              List.of(
                  CommonErrorCode.VERSION_CONFLICT,
                  CareItemErrorCode.ITEM_STATE_CONFLICT,
                  ProposalErrorCode.CHANGE_REQUEST_ALREADY_PENDING,
                  CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                  CommonErrorCode.REQUEST_IN_PROGRESS)));
      for (String suffix : List.of("/reconfirmations", "/answers")) {
        var mutation = api.getPaths().get("/api/spaces/{spaceId}/items/{itemId}" + suffix);
        if (mutation != null && mutation.getPost() != null)
          mutation
              .getPost()
              .setSecurity(
                  List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      }
      var answerBody = components.getSchemas().get("AnswerBody");
      if (answerBody != null) {
        answerBody.setRequired(
            List.of("answer", "basis", "expectedItemVersion", "clientRequestId"));
        answerBody.setAdditionalProperties(false);
      }
      var reconfirmationBody = api.getComponents().getSchemas().get("ReconfirmationBody");
      if (reconfirmationBody != null)
        reconfirmationBody.setRequired(
            List.of(
                "expectedItemVersion", "leaseToken", "values", "evidenceIds", "clientRequestId"));
      var reconfirmationValues = api.getComponents().getSchemas().get("ReconfirmationValues");
      if (reconfirmationValues != null)
        reconfirmationValues.setRequired(List.of("title", "fields"));
      for (String suffix : List.of("/{itemId}", "/{itemId}/status")) {
        var edit = api.getPaths().get("/api/spaces/{spaceId}/items" + suffix);
        if (edit != null && edit.getPatch() != null)
          edit.getPatch()
              .setSecurity(
                  List.of(new SecurityRequirement().addList("SessionCookie").addList("CsrfToken")));
      }
      var request = api.getComponents().getSchemas().get("CreateCareItemRequest");
      if (request != null) {
        request.setRequired(List.of("itemType", "title", "fields", "clientRequestId"));
        var itemType = new StringSchema();
        itemType.setEnum(List.of("SCHEDULE", "TASK", "QUESTION"));
        request.addProperty("itemType", itemType);
      }
    };
  }

  private ApiResponse conflict(Components components, String description, List<ErrorCode> codes) {
    var media = new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiResponse"));
    for (var code : codes) {
      var example =
          components
              .getResponses()
              .get(code.code())
              .getContent()
              .get("application/json")
              .getExample();
      media.addExamples(code.code(), new Example().summary(code.message()).value(example));
    }
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType("application/json", media));
  }
}
