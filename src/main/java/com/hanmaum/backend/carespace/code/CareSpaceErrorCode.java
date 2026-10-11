package com.hanmaum.backend.carespace.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum CareSpaceErrorCode implements ErrorCode {
  SPACE_LABEL_DUPLICATED(HttpStatus.CONFLICT, "같은 호칭의 돌봄 공간을 이미 소유하고 있습니다."),
  OWNER_TRANSFER_REQUIRED(HttpStatus.CONFLICT, "소유권을 이전하거나 공간을 삭제한 뒤 나갈 수 있습니다."),
  MEMBERSHIP_STATE_CONFLICT(HttpStatus.CONFLICT, "구성원의 현재 참여 상태와 역할을 다시 확인해주세요."),
  INVITATION_UNAVAILABLE(HttpStatus.NOT_FOUND, "사용할 수 없는 초대 링크입니다."),
  INVITATION_EXPIRED(HttpStatus.GONE, "초대 링크가 만료되었습니다. 가족에게 새 링크를 요청해주세요.");

  private final HttpStatus status;
  private final String message;

  CareSpaceErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  @Override
  public HttpStatus status() {
    return status;
  }

  @Override
  public String code() {
    return name();
  }

  @Override
  public String message() {
    return message;
  }
}
