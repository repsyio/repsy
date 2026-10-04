package io.repsy.os.server.shared.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assertions for verifying that API responses follow Decision 5 of the API guideline:
 * - Success bodies are bare resources (no RestResponse envelope with msgId)
 * - 201 Created responses include a Location header
 * - 204 No Content responses have empty bodies
 */
public class BareBodyAssertions {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private BareBodyAssertions() {
        // Utility class
    }

    /**
     * Asserts that a 201 Created response has:
     * - Location header present
     * - No msgId in the response body (bare resource)
     */
    public static void assertCreated201(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Location"))
            .as("201 response must have Location header")
            .isNotNull();
        assertNoMsgId(result);
    }

    /**
     * Asserts that a 204 No Content response has:
     * - Empty or no body
     */
    public static void assertNoContent204(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        try {
            String content = result.getResponse().getContentAsString();
            assertThat(content)
                .as("204 response must have empty body")
                .isEmpty();
        } catch (Exception e) {
            throw new AssertionError("Failed to read response body", e);
        }
    }

    /**
     * Asserts that a 202 Accepted response has:
     * - Location header present
     * - No msgId in the response body (bare resource)
     */
    public static void assertAccepted202(MvcResult result) {
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(result.getResponse().getHeader("Location"))
            .as("202 response must have Location header")
            .isNotNull();
        assertNoMsgId(result);
    }

    /**
     * Asserts that a response body does not contain msgId (i.e., it's a bare resource,
     * not wrapped in a RestResponse envelope).
     */
    public static void assertNoMsgId(MvcResult result) {
        try {
            String content = result.getResponse().getContentAsString();
            if (content.isEmpty()) {
                // Empty body is fine (e.g., 204 No Content)
                return;
            }

            JsonNode node = objectMapper.readTree(content);
            assertThat(node.has("msgId"))
                .as("Response body should not contain msgId (bare resource, not wrapped in RestResponse)")
                .isFalse();
        } catch (Exception e) {
            throw new AssertionError("Failed to parse response body", e);
        }
    }
}
