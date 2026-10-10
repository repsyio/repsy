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
package io.repsy.protocols.docker.protocol.handlers;

import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.DOCKER_UPLOAD_UUID;
import static io.repsy.protocols.docker.shared.utils.DockerProtocolHttpValues.RANGE;
import static org.springframework.http.HttpHeaders.LOCATION;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Handles {@code GET}/{@code HEAD} {@code /v2/{name}/blobs/uploads/{uuid}} — how a client resumes
 * an interrupted upload: it answers {@code 204} with the {@code Range} of the bytes already
 * written, or a {@code 404} ({@code io.repsy.core.error_handling.exceptions.ItemNotFoundException}
 * propagated from the facade) when the session does not exist.
 */
@NullMarked
public abstract class AbstractDockerUploadStatusProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<DockerProtocolFacade<ID>> {

  private static final Pattern UPLOAD_STATUS_PATTERN =
      Pattern.compile("^/([^/]+)/blobs/uploads/([0-9a-fA-F-]{36})/?$");

  public AbstractDockerUploadStatusProtocolMethodHandler(
      final PathParser basePathParser,
      final DockerProtocolFacade<ID> dockerFacade,
      final DockerProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.WRITE, HttpMethod.GET, HttpMethod.HEAD)
            .path(UPLOAD_STATUS_PATTERN.asMatchPredicate()),
        basePathParser,
        dockerFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);
    final var relativePath = urlProperties.getRelativePath().getPath();

    final var matcher = UPLOAD_STATUS_PATTERN.matcher(relativePath);

    if (!matcher.matches()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    final var imageName = matcher.group(1);
    final var sessionId = matcher.group(2);

    final var uploadPath = new RelativePath("/blobs/" + sessionId);

    // Throws ItemNotFoundException when no such upload session exists, which ErrorHandler turns
    // into the 404 BLOB_UPLOAD_UNKNOWN the OCI distribution spec asks for.
    final var uploadSize = this.facade.getUploadSize(context, uploadPath);

    final var location = this.getServletURILocation(context, imageName, sessionId);

    return ResponseEntity.noContent()
        .header(LOCATION, location)
        .header(RANGE, "0-" + Math.max(uploadSize - 1, 0))
        .header(DOCKER_UPLOAD_UUID, sessionId)
        .build();
  }

  protected String getServletURILocation(
      final ProtocolContext context, final String imageName, final String sessionId) {

    final var urlProperties = ProtocolContextUtils.getUrlProperties(context);

    return ServletUriComponentsBuilder.fromCurrentContextPath()
        .path("/v2/{repoName}/{imageName}/blobs/uploads/{sessionId}")
        .buildAndExpand(urlProperties.getRepoName(), imageName, sessionId)
        .toUriString();
  }
}
