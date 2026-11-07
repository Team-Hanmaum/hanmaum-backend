package com.hanmaum.backend.sharing.controller;

import com.hanmaum.backend.global.response.ApiResponse;
import com.hanmaum.backend.global.security.*;
import com.hanmaum.backend.sharing.dto.*;
import com.hanmaum.backend.sharing.service.ShareLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Sharing", description = "현재 참여자용 공유 링크. 초대·접근 권한 부여 및 외부 메시지 전송 제외")
public class ShareLinkController {
  private final ShareLinkService sharing;

  public ShareLinkController(ShareLinkService sharing) {
    this.sharing = sharing;
  }

  @PostMapping(value = "/spaces/{spaceId}/share-links", consumes = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "공유 링크 발급",
      description =
          "선택 항목 전체의 현재 권한·버전·상태를 확인한 뒤 링크와 최소 카드 정보를 발급합니다. 동일 요청은 최초 URL을 반환합니다. 자동 만료 없음.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      required = true,
      content = @Content(schema = @Schema(implementation = CreateBody.class)))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "발급 완료 또는 동일 요청 결과"),
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
        ref = "#/components/responses/CREATE_SHARE_LINK_CONFLICT"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "413",
        ref = "#/components/responses/PAYLOAD_TOO_LARGE"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  public ResponseEntity<ApiResponse<CreateShareLinkResponse>> create(
      @CurrentUser AuthenticatedUser user, @PathVariable UUID spaceId, @RequestBody JsonNode body) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(sharing.create(user.userId(), spaceId, body)));
  }

  @GetMapping("/share-links/{token}")
  @Operation(
      summary = "공유 대상 최신 내용 조회",
      description =
          "로그인한 현재 참여자만 조회할 수 있습니다. 삭제·재확인 항목은 상태와 item:null만 반환합니다. 과거 스냅샷 및 익명 미리보기 없음.",
      security = @SecurityRequirement(name = "SessionCookie"))
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "현재 권한으로 조회한 최신 항목과 상태"),
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
        responseCode = "500",
        ref = "#/components/responses/INTERNAL_ERROR")
  })
  public ResponseEntity<ApiResponse<SharedItemsResponse>> read(
      @CurrentUser AuthenticatedUser user, @PathVariable String token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(sharing.read(user.userId(), token)));
  }

  @Schema(name = "CreateShareLinkBody")
  public record CreateBody(UUID clientRequestId, List<TargetBody> targets) {}

  @Schema(name = "ShareLinkTargetBody")
  public record TargetBody(
      UUID itemId,
      @Schema(example = "3", description = "현재 항목의 1 이상 십진 정수 문자열 버전") String expectedItemVersion,
      @Schema(description = "변경 안내 공유 여부. true이면 현재 유효한 확정 변경 이력 필요") Boolean includesChange) {}
}
