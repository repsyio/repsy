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
package io.repsy.protocols.npm.protocol.handlers;

import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.protocol.facades.NpmProtocolFacade;
import io.repsy.protocols.npm.shared.utils.ExtractPath;
import io.repsy.protocols.shared.auth.BasicAuthChallenge;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.RequestBodies;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@NullMarked
public abstract class AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler
    extends AbstractFacadeProtocolMethodHandler<NpmProtocolFacade> {

  private static final Pattern PACKAGE_PATTERN = Pattern.compile("^/(.++)$");
  private static final String REV_MARKER = "/-rev/";

  private final long maxPublishBytes;

  /**
   * Parses the bounded publish body with a string-length ceiling as large as {@code
   * maxPublishBytes} itself (RPS-1561): Jackson's own default ({@code
   * StreamReadConstraints#DEFAULT_MAX_STRING_LEN}, 100,000,000 characters) sits below what the
   * other protocols allow for an upload (500MB), so a publish whose {@code _attachments} value (the
   * base64 tarball) is longer than that used to fail with an unrelated 500 even though the whole
   * body was well inside {@code maxPublishBytes}. The bytes handed to this mapper are already
   * bounded by {@link BoundedEntryReader} to at most {@code maxPublishBytes}, so no string it
   * parses can ever be longer than that either.
   */
  private final JsonMapper bodyMapper;

  public AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler(
      @Qualifier("osNpmPathParser") final PathParser basePathParser,
      final NpmProtocolFacade npmProtocolFacade,
      final NpmProtocolProvider provider,
      final long maxPublishBytes) {
    super(
        HandlerRoute.write(HttpMethod.PUT)
            .path(AbstractNpmPackagePublishOrDeprecateProtocolMethodHandler::isPublishOrDeprecate),
        basePathParser,
        npmProtocolFacade,
        provider);
    this.maxPublishBytes = maxPublishBytes;
    this.bodyMapper = boundedBodyMapper(maxPublishBytes);
  }

  private static JsonMapper boundedBodyMapper(final long maxPublishBytes) {
    final var maxStringLength = (int) Math.min(maxPublishBytes, (long) Integer.MAX_VALUE - 1024);
    return JsonMapper.builder(
            JsonFactory.builder()
                .streamReadConstraints(
                    StreamReadConstraints.builder().maxStringLength(maxStringLength).build())
                .build())
        .build();
  }

  /**
   * Must match the package pattern and not be login or dist-tags. A path with {@code /-rev/} is
   * part of an unpublish, which removes stored files and needs {@link Permission#MANAGE}: its
   * packument PUT belongs to {@link AbstractNpmPackageUnpublishProtocolMethodHandler}, so this
   * handler, which keeps {@link Permission#WRITE}, never matches it (RPS-1424).
   */
  private static boolean isPublishOrDeprecate(final String relativePath) {

    return PACKAGE_PATTERN.matcher(relativePath).matches()
        && !relativePath.contains("/-/user/") // Not login
        && !relativePath.contains("dist-tags") // Not dist-tags
        && !relativePath.contains(REV_MARKER); // Not an unpublish
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext protocolContext,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var relativePath = ProtocolContextUtils.getRelativePath(protocolContext).getPath();
    final var matcher = PACKAGE_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
          .body("Parser validation failed");
    }

    final var packagePath = matcher.group(1);

    try {
      // A client that declares an oversized body is refused before any of it is read (RPS-1561).
      if (request.getContentLengthLong() > this.maxPublishBytes) {
        throw new MaxUploadSizeExceededException(this.maxPublishBytes);
      }

      // The publish document is the whole body: none at all is the client's mistake, and used to
      // end in the JSON reader's "no content" failure, a 500 (RPS-1466). The read itself is bounded
      // too (RPS-1561), because a chunked body, or one that understates its Content-Length, can
      // still outgrow the limit while it is read; everything below Jackson's default string-length
      // ceiling used to be read into memory whole and a larger one failed with an unrelated 500
      // instead of 413.
      final var body =
          RequestBodies.nonEmpty(request.getInputStream())
              .orElseThrow(
                  () -> new BadRequestException(ProtocolErrorCodes.NPM_PUBLISH_BODY_EMPTY));

      final byte[] bytes;
      try {
        bytes =
            BoundedEntryReader.readAllBytes(
                body, request.getContentLengthLong(), this.maxPublishBytes);
      } catch (final EntryTooLargeException e) {
        throw new MaxUploadSizeExceededException(this.maxPublishBytes, e);
      }

      final var payload =
          this.bodyMapper.readValue(bytes, new TypeReference<Map<String, Object>>() {});

      final var pathVars = ExtractPath.extractPathVars(packagePath);
      final @Nullable String scopeName = pathVars.scopeName();
      final String packageName = pathVars.packageName();

      this.facade.publishOrDeprecate(protocolContext, scopeName, packageName, payload);

      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_JSON)
          .body(NpmWriteResponse.of(scopeName, packageName));
    } catch (final UnAuthorizedException _) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
          .header(WWW_AUTHENTICATE, BasicAuthChallenge.REPSY)
          .build();
    }
  }
}
