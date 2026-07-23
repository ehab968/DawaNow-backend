package com.example.dawanow.ai;

import com.example.dawanow.dtos.ai.ExtractedMedicine;
import com.example.dawanow.dtos.ai.ExtractedPrescription;
import com.example.dawanow.exception.PrescriptionAiUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class ItiPrescriptionAiClient implements PrescriptionAiClient {

    private static final Logger log = LoggerFactory.getLogger(ItiPrescriptionAiClient.class);
    private static final Set<String> OUTPUT_FIELDS = Set.of("medicines");
    private static final double COMPATIBILITY_CONFIDENCE = 1.0;
    private static final int MAX_TOKENS = 500;
    private static final String PROMPT = """
            Extract all medicine names from this handwritten prescription. If a medicine name is partially readable, infer the most likely medicine name based on common pharmaceutical names. Only omit a medicine if it is completely unreadable. Return ONLY valid JSON in exactly this format: {\\"medicines\\":[\\"medicine name\\"]}. Do not return dosage, strength, form, frequency, confidence scores, product IDs, prices, images, cart quantities, or any catalog data. Do not include markdown, code fences, explanations, or any additional text.
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String endpointUrl;
    private final String model;

    public ItiPrescriptionAiClient(
            RestClient restClient,
            ObjectMapper objectMapper,
            String endpointUrl,
            String model
    ) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("ITI prescription AI endpoint configuration is invalid");
        }
        if (model == null || !model.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("ITI prescription AI model configuration is invalid");
        }
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.endpointUrl = endpointUrl;
        this.model = model;
        log.info("ITI prescription AI client configured: endpointUrl={} model={}", endpointUrl, model);
    }

    @Override
    public ExtractedPrescription analyze(byte[] image, String contentType, String language, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw failure(HttpStatus.BAD_REQUEST, "X-AI-Api-Key header is required");
        }

        try {
            ObjectNode request = buildRequest(image, contentType);
            String requestBody = objectMapper.writeValueAsString(request);
            log.info("Sending ITI prescription AI request: endpointUrl={} model={} imageSize={} contentType={}", endpointUrl, model, image.length, contentType);
            log.info("ITI prescription AI request payload: {} Authorization=Bearer [REDACTED]", requestBody);
            ResponseEntity<String> response = restClient.post()
                    .uri(endpointUrl)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(String.class);

            if (response.getStatusCode().is3xxRedirection()) {
                log.warn(
                        "ITI prescription AI request redirected: category=unexpected-redirect status={} location={}",
                        response.getStatusCode(),
                        response.getHeaders().getFirst(HttpHeaders.LOCATION)
                );
                throw failure(HttpStatus.BAD_GATEWAY,
                        "The AI request was redirected before it reached the provider. "
                                + "Check that the backend can access the configured ITI endpoint without a network redirect.");
            }

            log.info(
                    "ITI AI response: status={} contentType={} contentLength={}",
                    response.getStatusCode(),
                    response.getHeaders().getContentType(),
                    response.getHeaders().getContentLength()
            );

            String responseBody = response.getBody();
            return parseResponse(responseBody == null ? null : objectMapper.readTree(responseBody));
        } catch (PrescriptionAiUnavailableException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw mapUpstreamHttpFailure(exception);
        } catch (ResourceAccessException exception) {
            throw mapResourceFailure(exception);
        } catch (RestClientException exception) {
            log.warn("ITI prescription AI request failed: category=client-error type={}",
                    exception.getClass().getSimpleName());
            throw failure(HttpStatus.BAD_GATEWAY, "Could not send the prescription to the AI provider");
        } catch (JsonProcessingException exception) {
            throw invalidResponse();
        }
    }

    private ObjectNode buildRequest(byte[] image, String contentType) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model_id", model);
        ObjectNode message = request.putArray("messages").addObject();
        message.put("role", "user");
        message.put("text", PROMPT);
        ObjectNode imageNode = message.putArray("images").addObject();
        imageNode.put("format", imageFormat(contentType));
        imageNode.put("data_base64", Base64.getEncoder().encodeToString(image));
        request.put("max_tokens", MAX_TOKENS);
        return request;
    }

    private String imageFormat(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> "jpeg";
            case "image/png" -> "png";
            default -> throw new IllegalArgumentException("Prescription image must be JPEG or PNG");
        };
    }

    private ExtractedPrescription parseResponse(JsonNode response) throws JsonProcessingException {
        if (response == null || !response.isObject()) {
            throw invalidResponse();
        }
        JsonNode outputText = response.get("output_text");
        if (outputText == null || !outputText.isTextual() || outputText.textValue().isBlank()) {
            throw invalidResponse();
        }

        JsonNode output = objectMapper.readTree(outputText.textValue());
        validateExactFields(output, OUTPUT_FIELDS);
        JsonNode medicines = output.get("medicines");
        if (!medicines.isArray()) {
            throw invalidResponse();
        }

        List<ExtractedMedicine> extractedMedicines = new ArrayList<>();
        for (JsonNode medicine : medicines) {
            if (!medicine.isTextual() || medicine.textValue().isBlank()) {
                throw invalidResponse();
            }
            String name = medicine.textValue().trim();
            extractedMedicines.add(new ExtractedMedicine(
                    name, name, null, null, COMPATIBILITY_CONFIDENCE
            ));
        }
        return new ExtractedPrescription(List.copyOf(extractedMedicines));
    }

    private void validateExactFields(JsonNode node, Set<String> expectedFields) {
        if (node == null || !node.isObject()) {
            throw invalidResponse();
        }
        Set<String> actualFields = new HashSet<>();
        node.fieldNames().forEachRemaining(actualFields::add);
        if (!actualFields.equals(expectedFields)) {
            throw invalidResponse();
        }
    }

    private PrescriptionAiUnavailableException mapUpstreamHttpFailure(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        log.warn("ITI AI error: status={}", status);
        return switch (status) {
            case 400 -> failure(HttpStatus.BAD_REQUEST,
                    "The AI provider rejected the prescription image or analysis request");
            case 401 -> failure(HttpStatus.UNAUTHORIZED,
                    "The AI API key is invalid or was not accepted");
            case 403 -> failure(HttpStatus.FORBIDDEN,
                    "The AI API key does not have permission to use this model");
            case 404 -> failure(HttpStatus.NOT_FOUND,
                    "The configured AI API endpoint or model was not found");
            case 429 -> failure(HttpStatus.TOO_MANY_REQUESTS,
                    "The AI provider rate limit was exceeded. Please try again later");
            default -> status >= 500
                    ? failure(HttpStatusCode.valueOf(status), "The AI provider is temporarily unavailable")
                    : failure(HttpStatusCode.valueOf(status),
                            "The AI provider rejected the prescription analysis request");
        };
    }

    private PrescriptionAiUnavailableException mapResourceFailure(ResourceAccessException exception) {
        Throwable cause = exception.getMostSpecificCause();
        boolean timeout = cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException;
        boolean connection = cause instanceof ConnectException;
        boolean prematureEof = cause instanceof EOFException;
        String category = timeout ? "timeout" : connection ? "connection" : prematureEof ? "premature-eof" : "network";
        String type = cause == null ? "unknown" : cause.getClass().getSimpleName();
        log.warn("ITI prescription AI request failed: category={} causeType={}", category, type);

        if (timeout) {
            return failure(HttpStatus.GATEWAY_TIMEOUT,
                    "The AI provider did not respond before the request timed out");
        }
        if (connection) {
            return failure(HttpStatus.BAD_GATEWAY, "Could not connect to the AI provider");
        }
        if (prematureEof) {
            return failure(HttpStatus.BAD_GATEWAY,
                    "The AI provider closed the connection before returning a response. Please try again later");
        }
        return failure(HttpStatus.BAD_GATEWAY,
                "A network error occurred while contacting the AI provider");
    }

    private PrescriptionAiUnavailableException invalidResponse() {
        log.warn("ITI prescription AI response failed validation: category=invalid-response");
        return failure(HttpStatus.BAD_GATEWAY,
                "The AI provider returned an invalid prescription analysis response");
    }

    private PrescriptionAiUnavailableException failure(HttpStatusCode status, String message) {
        return new PrescriptionAiUnavailableException(status, message);
    }
}
