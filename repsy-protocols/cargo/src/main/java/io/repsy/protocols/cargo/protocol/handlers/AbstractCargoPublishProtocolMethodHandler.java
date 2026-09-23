/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.core.error_handling.exceptions.ItemAlreadyExistException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@NullMarked
public abstract class AbstractCargoPublishProtocolMethodHandler implements ProtocolMethodHandler {

  private static final String PUBLISH_SUCCESS =
      "{\"warnings\":{\"invalid_categories\":[],\"invalid_badges\":[],\"other\":[]}}";

  private static final String VERSION_EXISTS_DETAIL =
      "this crate version already exists in this registry";

  private final PathParser basePathParser;
  private final CargoProtocolFacade facade;

  public AbstractCargoPublishProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.PUT.name().equals(request.getMethod())) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);

      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!relativePath.endsWith("/api/v1/crates/new")) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  /**
   * Answers in Cargo's error-body shape ({@code {"errors":[{"detail": "..."}]}}) only for the
   * deliberate, client-facing failures the facade throws on purpose (RPS-1072, RPS-1119, RPS-1141):
   * a validation failure, a crate or version that already exists, or the crate/its metadata being
   * larger than the configured limit. Anything else (an {@link IOException} from storage, a
   * database failure, ...) is a genuine server fault rather than something the client did wrong, so
   * it is left to propagate to {@code ErrorHandler}, which answers 500 with a generic error code
   * instead of the raw exception message that used to reach the client here.
   */
  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    try {
      this.facade.publish(context, request.getInputStream());
      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(PUBLISH_SUCCESS);
    } catch (final IllegalArgumentException e) {
      return cargoError(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (final ItemAlreadyExistException _) {
      // The exception carries a fixed msgId (RPS-1127), which is not a sentence for cargo to show.
      return cargoError(HttpStatus.BAD_REQUEST, VERSION_EXISTS_DETAIL);
    } catch (final MaxUploadSizeExceededException e) {
      return cargoError(HttpStatus.PAYLOAD_TOO_LARGE, "the crate exceeds the maximum upload size");
    }
  }

  private static ResponseEntity<Object> cargoError(
      final HttpStatus status, final @Nullable String detail) {

    return ResponseEntity.status(status)
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(CargoErrorResponse.of(detail));
  }
}
