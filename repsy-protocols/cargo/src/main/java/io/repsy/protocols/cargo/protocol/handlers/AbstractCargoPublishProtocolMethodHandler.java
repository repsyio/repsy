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
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facades.contracts.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.constants.CargoConstants;
import io.repsy.protocols.shared.dtos.ProtocolErrorBody;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@NullMarked
public abstract class AbstractCargoPublishProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<CargoProtocolFacade> {

  private static final String PUBLISH_SUCCESS =
      "{\"warnings\":{\"invalid_categories\":[],\"invalid_badges\":[],\"other\":[]}}";

  private static final String VERSION_EXISTS_DETAIL =
      "this crate version already exists in this registry";

  public AbstractCargoPublishProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade facade,
      final CargoProtocolProvider provider) {

    super(
        HandlerRoute.write(HttpMethod.PUT).path(path -> path.endsWith("/api/v1/crates/new")),
        basePathParser,
        facade,
        provider);
  }

  /**
   * Answers in Cargo's error-body shape ({@code {"errors":[{"detail": "..."}]}}) only for the
   * deliberate, client-facing failures the facade throws on purpose (RPS-1072, RPS-1119, RPS-1141):
   * a validation failure, a crate or version that already exists, or the crate/its metadata being
   * larger than the configured limit. Anything else (an {@link IOException} from storage, a
   * database failure, ...) is a genuine server fault rather than something the client did wrong, so
   * it is left to propagate to {@code ErrorHandler}, which picks the status (500, or 503 with
   * {@code Retry-After} when the storage could not be written) and a generic error code instead of
   * the raw exception message that used to reach the client here. The request is marked with {@link
   * CargoConstants#ERROR_BODY_ATTRIBUTE}, so that answer, too, is in Cargo's error-body shape and
   * not the RestResponse envelope (RPS-2104).
   */
  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws IOException {

    request.setAttribute(CargoConstants.ERROR_BODY_ATTRIBUTE, true);

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
        .body(ProtocolErrorBody.withDetail(detail));
  }
}
