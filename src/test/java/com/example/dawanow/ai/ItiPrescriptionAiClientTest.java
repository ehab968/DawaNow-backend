package com.example.dawanow.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.example.dawanow.exception.PrescriptionAiUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.EOFException;
import java.net.SocketTimeoutException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class ItiPrescriptionAiClientTest {

    private static final String ENDPOINT_URL = "http://iti.test/api/v1/student/multimodal-chat";
    private static final String MODEL = "qwen.qwen3-vl-235b-a22b";
    private static final String API_KEY = "test-secret-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockRestServiceServer server;
    private ItiPrescriptionAiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ItiPrescriptionAiClient(builder.build(), objectMapper, ENDPOINT_URL, MODEL);
    }

    @Test
    void sendsExpectedRequestAndMapsNamesToCompatibilityFields(CapturedOutput capturedOutput) throws Exception {
        byte[] image = {1, 2, 3, 4};
        String encodedImage = Base64.getEncoder().encodeToString(image);

        server.expect(requestTo(ENDPOINT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.model_id").value(MODEL))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value(containsString("Return ONLY valid JSON")))
                .andExpect(jsonPath("$.messages[0].images[0].format").value("jpeg"))
                .andExpect(jsonPath("$.messages[0].images[0].data_base64").value(encodedImage))
                .andExpect(jsonPath("$.max_tokens").value(500))
                .andExpect(content().string(not(containsString("productId"))))
                .andRespond(withSuccess(providerResponse("{\"medicines\":[\"Panadol Extra\",\"Abilify\"]}"),
                        MediaType.APPLICATION_JSON));

        var result = client.analyze(image, "image/jpeg", "en", API_KEY);

        assertThat(result.medicines()).hasSize(2);
        assertThat(result.medicines().getFirst()).satisfies(medicine -> {
            assertThat(medicine.rawText()).isEqualTo("Panadol Extra");
            assertThat(medicine.name()).isEqualTo("Panadol Extra");
            assertThat(medicine.strength()).isNull();
            assertThat(medicine.form()).isNull();
            assertThat(medicine.confidence()).isEqualTo(1.0);
        });
        assertThat(capturedOutput)
                .contains("Sending ITI prescription AI request")
                .contains(encodedImage)
                .contains("Authorization=Bearer [REDACTED]")
                .doesNotContain(API_KEY);
        server.verify();
    }

    @Test
    void sendsPngFormat() throws Exception {
        server.expect(requestTo(ENDPOINT_URL))
                .andExpect(jsonPath("$.messages[0].images[0].format").value("png"))
                .andRespond(withSuccess(providerResponse("{\"medicines\":[]}"), MediaType.APPLICATION_JSON));

        assertThat(client.analyze(new byte[]{1}, "image/png", "ar", API_KEY).medicines()).isEmpty();
        server.verify();
    }

    @Test
    void rejectsMalformedOrUnexpectedOutput() throws Exception {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withSuccess(providerResponse("{\"medicines\":[\"Panadol\"],\"confidence\":1}"),
                        MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY));
    }

    @Test
    void rejectsProviderErrorBodyReturnedAsSuccess() {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withSuccess("""
                        {"error":{"code":"INTERNAL_ERROR","message":"Unexpected server error.","details":{}}}
                        """, MediaType.APPLICATION_JSON));

        assertInvalidResponse(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY));
    }

    @Test
    void requiresPerRequestApiKeyWithoutCallingProvider() {
        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", " "))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getMessage()).isEqualTo("X-AI-Api-Key header is required");
                });
        server.verify();
    }

    @Test
    void mapsInvalidApiKeyToUnauthorized() {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo("The AI API key is invalid or was not accepted");
                });
        server.verify();
    }

    @Test
    void mapsUnexpectedRedirectToBadGatewayWithoutTreatingItAsAiOutput() {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withStatus(HttpStatus.TEMPORARY_REDIRECT)
                        .header(HttpHeaders.LOCATION, "http://network.example/redirect"));

        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).contains("redirected before it reached the provider");
                });
        server.verify();
    }

    @Test
    void mapsProviderServerFailureWithoutExposingResponse(CapturedOutput capturedOutput) {
        String providerBody = """
                {"error":{"code":"INTERNAL_ERROR","message":"Unexpected server error.","details":{}}}
                """;
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(providerBody));

        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("The AI provider is temporarily unavailable");
                });
        assertThat(capturedOutput).doesNotContain(providerBody).doesNotContain(API_KEY);
        server.verify();
    }

    @Test
    void mapsTimeoutToGatewayTimeout() {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withException(new SocketTimeoutException("timed out")));

        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
                    assertThat(exception.getMessage()).contains("timed out");
                });
        server.verify();
    }

    @Test
    void mapsPrematureEofToBadGatewayWithClearMessage() {
        server.expect(requestTo(ENDPOINT_URL))
                .andRespond(withException(new EOFException("connection closed by upstream")));

        assertThatThrownBy(() -> client.analyze(new byte[]{1}, "image/jpeg", "en", API_KEY))
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).isEqualTo(
                            "The AI provider closed the connection before returning a response. Please try again later");
                });
        server.verify();
    }

    private String providerResponse(String outputText) throws JsonProcessingException {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("request_id", "request-1");
        response.put("model_id", MODEL);
        response.put("output_text", outputText);
        response.put("status", "active");
        return objectMapper.writeValueAsString(response);
    }

    private void assertInvalidResponse(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(PrescriptionAiUnavailableException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(exception.getMessage()).isEqualTo(
                            "The AI provider returned an invalid prescription analysis response");
                });
        server.verify();
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
