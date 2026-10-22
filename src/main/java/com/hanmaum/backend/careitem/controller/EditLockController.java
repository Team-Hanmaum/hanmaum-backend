package com.hanmaum.backend.careitem.controller;

import com.hanmaum.backend.careitem.dto.EditLockResponse;
import com.hanmaum.backend.careitem.service.*;
import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(
    value = "/api/spaces/{spaceId}/items/{itemId}/edit-lock",
    produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CareItem")
@SecurityRequirement(name = "SessionCookie")
@SecurityRequirement(name = "CsrfToken")
@ApiResponses({
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      ref = "#/components/responses/INVALID_REQUEST"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "401",
      ref = "#/components/responses/UNAUTHENTICATED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "403",
      ref = "#/components/responses/FORBIDDEN"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      ref = "#/components/responses/RESOURCE_NOT_FOUND"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "409",
      description = "VERSION_CONFLICT / ITEM_LOCKED / EDIT_LOCK_EXPIRED"),
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "500",
      ref = "#/components/responses/INTERNAL_ERROR")
})
public class EditLockController {
  private final EditLockService locks;

  public EditLockController(EditLockService locks) {
    this.locks = locks;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "항목 편집 잠금 획득",
      description =
          "현재 참여자만 가능. 기존 유효 잠금은 같은 사용자도 재획득 불가. 기본 2분, 권장 갱신 30초. 응답 유실 시 토큰 재발급 없이 만료 후 재획득.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content =
          @Content(
              schema = @Schema(implementation = AcquireBody.class),
              examples = @ExampleObject(value = "{\"expectedItemVersion\":\"1\"}")))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "201",
      description = "잠금 획득. leaseToken은 이 응답에서만 제공")
  public ResponseEntity<ApiResponse<EditLockResponse>> acquire(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @RequestBody JsonNode body) {
    CareMutationInput.object(body, "body", Set.of("expectedItemVersion"));
    long version =
        CareMutationInput.version(body.path("expectedItemVersion"), "expectedItemVersion", true);
    return ResponseEntity.status(201)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(locks.acquire(user.userId(), spaceId, itemId, version)));
  }

  @PostMapping(value = "/renewals", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(summary = "항목 편집 잠금 갱신", description = "현재 참여자이면서 토큰 보유자만 갱신. 만료된 잠금은 되살리지 않음.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = TokenBody.class)))
  public ResponseEntity<ApiResponse<EditLockResponse>> renew(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @RequestBody JsonNode body) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(locks.renew(user.userId(), spaceId, itemId, token(body))));
  }

  @PostMapping(value = "/releases", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "항목 편집 잠금 해제",
      description = "유효 잠금이 없으면 204. 다른 사람의 새 잠금은 해제하지 않음. 성공 저장 시 서버가 잠금을 해제.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = TokenBody.class)))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "204",
      description = "해제 완료, 본문 없음")
  public ResponseEntity<Void> release(
      @CurrentUser AuthenticatedUser user,
      @PathVariable UUID spaceId,
      @PathVariable UUID itemId,
      @RequestBody JsonNode body) {
    locks.release(user.userId(), spaceId, itemId, token(body));
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  private String token(JsonNode body) {
    CareMutationInput.object(body, "body", Set.of("leaseToken"));
    return CareMutationInput.text(body.path("leaseToken"), "leaseToken", true);
  }

  public record AcquireBody(@Schema(example = "1") String expectedItemVersion) {}

  public record TokenBody(@Schema(description = "획득 응답의 본인 leaseToken") String leaseToken) {}
}
