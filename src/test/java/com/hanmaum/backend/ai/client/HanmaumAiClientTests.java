package com.hanmaum.backend.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.hanmaum.backend.ai.dto.AiAnalysisRequest;
import com.hanmaum.backend.ai.dto.AiAnalysisRequest.CandidateItem;
import com.hanmaum.backend.ai.dto.AiAnalysisResponse.Operation;
import com.hanmaum.backend.ai.dto.CareItemType;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HanmaumAiClientTests {
  private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private ValidatorFactory validators;
  private MockRestServiceServer server;
  private HanmaumAiClient client;

  @BeforeEach
  void setUp() {
    var builder =
        RestClient.builder()
            .baseUrl("http://ai.test")
            .defaultHeader("X-Internal-Api-Key", "test-only-key");
    server = MockRestServiceServer.bindTo(builder).build();
    validators = Validation.buildDefaultValidatorFactory();
    client = new HanmaumAiClient(builder.build(), validators.getValidator());
  }

  @AfterEach
  void tearDown() {
    validators.close();
    server.verify();
  }

  @Test
  void sendsTheContractAndReturnsAnUnconfirmedUpdate() {
    server
        .expect(requestTo("http://ai.test/v1/analyses"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Internal-Api-Key", "test-only-key"))
        .andExpect(header("X-Request-Id", REQUEST_ID.toString()))
        .andExpect(jsonPath("$.content").value("병원이 20일로 바뀌었대"))
        .andExpect(jsonPath("$.candidates[0].version").value(2))
        .andRespond(
            withSuccess(
                response(REQUEST_ID, "\"" + ITEM_ID + "\"", "2", "[]", "20일"),
                MediaType.APPLICATION_JSON));
    var response = client.analyze(request());
    assertThat(response.suggestions().getFirst().operation()).isEqualTo(Operation.UPDATE);
    assertThat(response.suggestions().getFirst().expectedVersion()).isEqualTo(2);
  }

  @Test
  void preservesAmbiguityForUserSelection() {
    server
        .expect(requestTo("http://ai.test/v1/analyses"))
        .andRespond(
            withSuccess(
                response(REQUEST_ID, "null", "null", "[\"" + ITEM_ID + "\"]", "20일"),
                MediaType.APPLICATION_JSON));
    var suggestion = client.analyze(request()).suggestions().getFirst();
    assertThat(suggestion.targetItemId()).isNull();
    assertThat(suggestion.candidateItemIds()).containsExactly(ITEM_ID);
  }

  @Test
  void rejectsTargetsOutsideTheProvidedCandidates() {
    expectResponse(response(REQUEST_ID, "\"" + UUID.randomUUID() + "\"", "2", "[]", "20일"));
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void rejectsAChangedCandidateVersion() {
    expectResponse(response(REQUEST_ID, "\"" + ITEM_ID + "\"", "1", "[]", "20일"));
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void rejectsEvidenceThatIsNotInTheOriginalRecord() {
    expectResponse(response(REQUEST_ID, "\"" + ITEM_ID + "\"", "2", "[]", "혈압약 변경"));
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void rejectsAResponseForAnotherRequest() {
    expectResponse(response(UUID.randomUUID(), "\"" + ITEM_ID + "\"", "2", "[]", "20일"));
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void rejectsMissingResponseFields() {
    expectResponse("{\"requestId\":\"" + REQUEST_ID + "\"}");
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void sanitizesUpstreamFailures() {
    server
        .expect(requestTo("http://ai.test/v1/analyses"))
        .andRespond(withServerError().body("private-source-record"));
    assertThatThrownBy(() -> client.analyze(request()))
        .isInstanceOf(AiServiceException.class)
        .hasMessageNotContaining("private-source-record")
        .hasNoCause();
  }

  @Test
  void reportsTimeoutsWithoutRetrying() {
    server
        .expect(requestTo("http://ai.test/v1/analyses"))
        .andRespond(withException(new SocketTimeoutException("timeout")));
    assertThatThrownBy(() -> client.analyze(request())).isInstanceOf(AiServiceException.class);
  }

  @Test
  void rejectsInvalidInputBeforeCallingAi() {
    var invalid =
        new AiAnalysisRequest(
            REQUEST_ID,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "",
            Instant.now(),
            "Asia/Seoul",
            List.of());
    assertThatThrownBy(() -> client.analyze(invalid))
        .isInstanceOf(ConstraintViolationException.class);
  }

  private void expectResponse(String response) {
    server
        .expect(requestTo("http://ai.test/v1/analyses"))
        .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
  }

  private AiAnalysisRequest request() {
    return new AiAnalysisRequest(
        REQUEST_ID,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "병원이 20일로 바뀌었대",
        Instant.parse("2026-09-26T12:00:00Z"),
        "Asia/Seoul",
        List.of(new CandidateItem(ITEM_ID, 2L, CareItemType.SCHEDULE, "10월 15일 재진")));
  }

  private String response(
      UUID requestId, String target, String version, String candidates, String evidence) {
    return """
        {"requestId":"%s","suggestions":[{
          "operation":"UPDATE","type":"SCHEDULE","targetItemId":%s,
          "expectedVersion":%s,"candidateItemIds":%s,"title":"재진 일정 변경",
          "evidence":"%s","changes":{"date":"2026-10-20"}
        }]}
        """
        .formatted(requestId, target, version, candidates, evidence);
  }
}
