package com.hanmaum.backend.proposal.controller;

import static org.assertj.core.api.Assertions.*;

import com.hanmaum.backend.careitem.service.CareMutationSizeLimits;
import com.hanmaum.backend.global.exception.ApiException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import tools.jackson.databind.JsonNode;

class ProposalDecisionBodyAdviceTests {
  private final ProposalDecisionBodyAdvice advice = new ProposalDecisionBodyAdvice();

  @Test
  void missingOrUnderstatedLengthCannotBypassStreamingLimit() {
    for (long declared : new long[] {-1, 1}) {
      var read = new AtomicInteger();
      var stream =
          new InputStream() {
            @Override
            public int read() {
              read.incrementAndGet();
              return ' ';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
              Arrays.fill(buffer, offset, offset + length, (byte) ' ');
              read.addAndGet(length);
              return length;
            }
          };
      assertThatThrownBy(
              () -> advice.beforeBodyRead(input(stream, declared), null, JsonNode.class, null))
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.code().code()).isEqualTo("PAYLOAD_TOO_LARGE"));
      assertThat(read.get()).isEqualTo(CareMutationSizeLimits.REQUEST_BYTES + 1);
    }
  }

  @Test
  void oversizedDeclaredLengthIsRejectedWithoutReadingTheBody() {
    var stream =
        new InputStream() {
          @Override
          public int read() {
            throw new AssertionError("Body must not be read");
          }
        };
    assertThatThrownBy(
            () ->
                advice.beforeBodyRead(
                    input(stream, (long) CareMutationSizeLimits.REQUEST_BYTES + 1),
                    null,
                    JsonNode.class,
                    null))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code().code()).isEqualTo("PAYLOAD_TOO_LARGE"));
  }

  private HttpInputMessage input(InputStream stream, long declared) {
    var headers = new HttpHeaders();
    if (declared >= 0) headers.setContentLength(declared);
    return new HttpInputMessage() {
      @Override
      public InputStream getBody() {
        return stream;
      }

      @Override
      public HttpHeaders getHeaders() {
        return headers;
      }
    };
  }
}
