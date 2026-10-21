package com.hanmaum.backend.ai.client.v2;

import static com.hanmaum.backend.ai.client.v2.V2Fixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

import com.hanmaum.backend.ai.code.AiFailureCode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiV2PayloadLimitTests {
  @Test
  void oversizedSemanticRequestsAreRejectedBeforeHttpEvenWhenTheyFitTheByteBudget()
      throws Exception {
    var builder = RestClient.builder().baseUrl("http://ai.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = new HanmaumAiV2Client(builder.build(), SETTINGS, AI);
    var base = new AiV2Contract(LIMITS).parseRequest(raw(BASE));
    var oversized =
        new com.hanmaum.backend.ai.dto.v2.AiV2AnalysisRequest(
            base.requestId(),
            base.careSpaceId(),
            base.recordId(),
            "a".repeat(10_001),
            base.recordedAt(),
            base.timezone(),
            base.candidates());
    assertFailure(
        catchThrowable(() -> client.analyze(oversized)), AiFailureCode.AI_INTEGRATION_ERROR);
    server.verify();
  }

  @Test
  void errorEnvelopesAlsoRespectTheTotalDecodedStringLimit() {
    var mapper = new AiV2ErrorMapper();
    String code = "AI_OVERLOADED";
    for (int delta : new int[] {0, 1}) {
      byte[] body =
          JSON.writeValueAsBytes(
              JSON.createObjectNode()
                  .put("code", code)
                  .put("message", "a".repeat(50_000 - code.length() + delta)));
      assertThat(mapper.map(503, body, LIMITS.responseBytes()))
          .isEqualTo(
              delta == 0
                  ? AiFailureCode.AI_SERVICE_UNAVAILABLE
                  : AiFailureCode.AI_INVALID_RESPONSE);
    }
  }

  @Test
  void missingOrInvalidBudgetsAndOversizedRequestsNeverReachHttp() throws Exception {
    var builder = RestClient.builder().baseUrl("http://ai.test");
    var server = MockRestServiceServer.bindTo(builder).build();
    var transport = builder.build();
    var request = new AiV2Contract(LIMITS).parseRequest(raw(BASE));
    for (var settings :
        java.util.List.of(
            new AiV2Properties(null, null),
            new AiV2Properties(100, null),
            new AiV2Properties(null, 100),
            new AiV2Properties(0, 100),
            new AiV2Properties(100, -1),
            new AiV2Properties(1, 100))) {
      var client = new HanmaumAiV2Client(transport, settings, AI);
      assertFailure(
          catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_INTEGRATION_ERROR);
    }
    server.verify();
  }

  @Test
  void rejectsOversizedDeclaredBodiesBeforeReadingAndClosesTheStream() throws Exception {
    verifyOversizedStream(65, 0);
  }

  @Test
  void stopsUnknownLengthBodiesAtOneSentinelByteAndClosesTheStream() throws Exception {
    verifyOversizedStream(-1, 65);
  }

  @Test
  void doesNotTrustAnUnderstatedContentLength() throws Exception {
    verifyOversizedStream(10, 65);
  }

  private void verifyOversizedStream(long declaredLength, int expectedBytesRead) throws Exception {
    for (HttpStatus status : java.util.List.of(HttpStatus.OK, HttpStatus.BAD_GATEWAY)) {
      var consumed = new AtomicInteger();
      var closed = new AtomicBoolean();
      InputStream endless =
          new InputStream() {
            @Override
            public int read() {
              consumed.incrementAndGet();
              return ' ';
            }

            @Override
            public void close() {
              closed.set(true);
            }
          };
      var builder = RestClient.builder().baseUrl("http://ai.test");
      var server = MockRestServiceServer.bindTo(builder).build();
      server
          .expect(requestTo("http://ai.test/v2/analyses"))
          .andRespond(
              sent -> {
                var response = new MockClientHttpResponse(endless, status);
                if (declaredLength >= 0) response.getHeaders().setContentLength(declaredLength);
                return response;
              });
      var client =
          new HanmaumAiV2Client(builder.build(), new AiV2Properties(LIMITS.requestBytes(), 64), AI);
      var request = new AiV2Contract(LIMITS).parseRequest(raw(BASE));
      assertFailure(
          catchThrowable(() -> client.analyze(request)), AiFailureCode.AI_INVALID_RESPONSE);
      assertThat(consumed.get()).isEqualTo(expectedBytesRead);
      assertThat(closed).isTrue();
      server.verify();
    }
  }

  @Test
  void acceptsExactlyTheBudgetAndRejectsAnAdditionalByte() throws Exception {
    byte[] body = "한글".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(AiV2PayloadLimits.read(new ByteArrayInputStream(body), -1, body.length))
        .isEqualTo(body);
    assertThatThrownBy(
            () -> AiV2PayloadLimits.read(new ByteArrayInputStream(body), -1, body.length - 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid AI v2 contract.");
    var output = new AiV2PayloadLimits.LimitedBytes(1);
    output.write(1);
    assertThatThrownBy(() -> output.write(2)).isInstanceOf(IllegalArgumentException.class);
    assertThat(output.size()).isEqualTo(1);
  }
}
