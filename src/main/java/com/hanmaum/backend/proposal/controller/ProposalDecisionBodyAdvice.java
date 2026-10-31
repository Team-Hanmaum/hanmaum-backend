package com.hanmaum.backend.proposal.controller;

import com.hanmaum.backend.careitem.controller.CareItemController;
import com.hanmaum.backend.careitem.service.CareMutationSizeLimits;
import com.hanmaum.backend.global.code.CommonErrorCode;
import com.hanmaum.backend.global.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Bounds raw bytes before the JSON converter, including bodies without Content-Length. */
@ControllerAdvice(
    assignableTypes = {
      ProposalDecisionController.class,
      ChangeRequestController.class,
      CareItemController.class
    })
public class ProposalDecisionBodyAdvice extends RequestBodyAdviceAdapter {
  @Override
  public boolean supports(
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converter) {
    return true;
  }

  @Override
  public HttpInputMessage beforeBodyRead(
      HttpInputMessage input,
      MethodParameter parameter,
      Type targetType,
      Class<? extends HttpMessageConverter<?>> converter)
      throws IOException {
    if (input.getHeaders().getContentLength() > CareMutationSizeLimits.REQUEST_BYTES)
      throw tooLarge();
    byte[] body = input.getBody().readNBytes(CareMutationSizeLimits.REQUEST_BYTES + 1);
    if (body.length > CareMutationSizeLimits.REQUEST_BYTES) throw tooLarge();
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return new ByteArrayInputStream(body);
      }

      @Override
      public HttpHeaders getHeaders() {
        return input.getHeaders();
      }
    };
  }

  private ApiException tooLarge() {
    return new ApiException(CommonErrorCode.PAYLOAD_TOO_LARGE);
  }
}
