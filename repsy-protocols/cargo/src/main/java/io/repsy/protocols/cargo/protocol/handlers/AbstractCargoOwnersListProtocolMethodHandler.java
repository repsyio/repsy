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

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.shared.crate.dtos.CargoOwnerUser;
import io.repsy.protocols.cargo.shared.crate.dtos.CargoOwnersResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /api/v1/crates/{name}/owners}: {@code cargo owner --list}. Repsy has no ownership
 * model finer than the repository itself (no owner column, no ACL table, no authenticated principal
 * reaches this handler), so the response is a repo-level synthetic owner. The real client models
 * the response as a struct with a required {@code users: Vec<User>} field, so it must always be
 * present and an array, even though there is nothing finer to report.
 */
@NullMarked
public abstract class AbstractCargoOwnersListProtocolMethodHandler
    implements ProtocolMethodHandler {

  private static final Pattern OWNERS_PATTERN = Pattern.compile(".*/api/v1/crates/[^/]+/owners$");
  private static final String OWNERSHIP_MESSAGE =
      "Ownership is managed at the repository level in this registry";

  private final PathParser basePathParser;

  protected AbstractCargoOwnersListProtocolMethodHandler(
      final PathParser basePathParser, final CargoProtocolProvider provider) {

    this.basePathParser = basePathParser;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ, "writeOperation", false);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var method = HttpMethod.valueOf(request.getMethod());
      if (!this.getSupportedMethods().contains(method)) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!OWNERS_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var repoInfo = ProtocolContextUtils.<Object>getRepoInfo(context);
    final var owner = new CargoOwnerUser(0, repoInfo.getName(), OWNERSHIP_MESSAGE);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(new CargoOwnersResponse(List.of(owner)));
  }
}
