package com.hanmaum.backend.proposal.code;

import com.hanmaum.backend.global.code.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProposalErrorCode implements ErrorCode {
  CHANGE_REQUEST_ALREADY_PENDING(HttpStatus.CONFLICT, "같은 제안에 대한 검토 대기 요청이 이미 있습니다."),
  CHANGE_REQUEST_STATE_CONFLICT(HttpStatus.CONFLICT, "변경 요청의 처리 상태 또는 검토 조건이 변경되었습니다. 다시 확인해주세요."),
  PROPOSAL_BATCH_CONFLICT(HttpStatus.CONFLICT, "선택한 제안을 함께 반영할 수 없습니다."),
  PROPOSAL_STATE_CONFLICT(HttpStatus.CONFLICT, "제안의 처리 상태 또는 검토 조건이 변경되었습니다. 다시 확인해주세요.");

  private final HttpStatus status;
  private final String message;

  ProposalErrorCode(HttpStatus status, String message) {
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
