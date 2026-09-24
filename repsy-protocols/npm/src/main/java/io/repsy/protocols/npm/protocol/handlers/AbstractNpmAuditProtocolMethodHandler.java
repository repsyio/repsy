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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.npm.protocol.NpmProtocolProvider;
import io.repsy.protocols.npm.shared.audit.InvalidAuditRequestException;
import io.repsy.protocols.npm.shared.audit.NpmAdvisorySource;
import io.repsy.protocols.npm.shared.audit.NpmAuditRequestReader;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the audit endpoints have in common: {@code POST}, read access to the repository, and a JSON
 * body that may be gzip compressed. A body that cannot be used is answered {@code 400}, and one
 * that inflates past {@link #maxAuditBodyBytes()} is answered {@code 413}. The errors are answered
 * here and not thrown, so that the npm client, which falls back to another endpoint on an error,
 * gets a plain JSON error.
 */
@NullMarked
public abstract class AbstractNpmAuditProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  /** The largest inflated body an audit request may have. */
  static final long DEFAULT_MAX_AUDIT_BODY_BYTES = 64L * 1024 * 1024;

  private final PathParser basePathParser;
  private final String relativePathRegex;
  private final NpmAdvisorySource<ID> advisorySource;
  private final ObjectMapper objectMapper;

  protected AbstractNpmAuditProtocolMethodHandler(
      final PathParser basePathParser,
      final String relativePathRegex,
      final NpmAdvisorySource<ID> advisorySource,
      final ObjectMapper objectMapper,
      final NpmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.relativePathRegex = relativePathRegex;
    this.advisorySource = advisorySource;
    this.objectMapper = objectMapper;

    provider.registerMethodHandler(this);
  }

  /** Builds the report of an audit request; {@code body} is its JSON object. */
  protected abstract Object report(BaseRepoInfo<ID> repoInfo, JsonNode body);

  /** Where the advisories of the requested versions come from. */
  protected final NpmAdvisorySource<ID> advisorySource() {
    return this.advisorySource;
  }

  /** The largest inflated request body to accept. */
  protected long maxAuditBodyBytes() {
    return DEFAULT_MAX_AUDIT_BODY_BYTES;
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "writeOperation", false,
        "skipUsagePostProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return new NpmExactPathParser(this.basePathParser, HttpMethod.POST, this.relativePathRegex);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    try {
      final var body =
          NpmAuditRequestReader.read(
              request.getInputStream(),
              request.getHeader(HttpHeaders.CONTENT_ENCODING),
              this.objectMapper,
              this.maxAuditBodyBytes());

      final var report = this.report(ProtocolContextUtils.<ID>getRepoInfo(context), body);

      return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(report);

    } catch (final EntryTooLargeException _) {
      return error(HttpStatus.CONTENT_TOO_LARGE, "audit request body too large");
    } catch (final InvalidAuditRequestException _) {
      return error(HttpStatus.BAD_REQUEST, "invalid audit request body");
    }
  }

  private static ResponseEntity<Object> error(final HttpStatus status, final String message) {
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("error", message));
  }
}
