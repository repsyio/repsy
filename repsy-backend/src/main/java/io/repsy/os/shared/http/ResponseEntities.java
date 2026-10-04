package io.repsy.os.shared.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

/**
 * Helper methods for constructing HTTP response entities following the panel API guidelines
 * (Decision 5: bare resource on success, 201 + Location on create, 204 on delete/empty update).
 */
public class ResponseEntities {
    private ResponseEntities() {
        // Utility class
    }

    /**
     * Creates a 201 Created response with the given URI in the Location header and the resource
     * body.
     *
     * @param location the URI of the newly created resource
     * @param body     the resource that was created
     * @return a ResponseEntity with 201 status, Location header, and the body
     */
    public static <T> ResponseEntity<T> created(URI location, T body) {
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .location(location)
            .body(body);
    }

    /**
     * Creates a 204 No Content response for successful deletes and empty updates.
     *
     * @return a ResponseEntity with 204 status and no body
     */
    public static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().build();
    }

    /**
     * Creates a 202 Accepted response for asynchronous operations, with the given URI in the
     * Location header.
     *
     * @param location the URI of the status resource for the async operation
     * @return a ResponseEntity with 202 status and Location header
     */
    public static ResponseEntity<Void> accepted(URI location) {
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .location(location)
            .build();
    }
}
