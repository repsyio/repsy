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
package io.repsy.os.server.protocols.docker.protocol.pre_processors;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.server.shared.utils.RequestBaseUrlUtils;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The {@code WWW-Authenticate} challenge of the Docker registry: a Bearer realm that points the
 * client to the token endpoint and names the scope the request needs. It answers a request without
 * credentials as well as one whose credentials were rejected.
 */
@UtilityClass
@NullMarked
final class DockerAuthChallenge {

  /**
   * Builds the challenge for a request that names an image or not, with the token endpoint of the
   * host the client called. The {@code scope} is what the failed request needs, as the token spec
   * says: containerd, oras-go and crane copy it into their token request. The registry-level ping
   * ({@code GET /v2/}) names no image, so its challenge carries no scope (RPS-1588).
   *
   * @param request The unauthenticated or rejected request
   * @param name The {@code <repo>/<image>} the request addresses, or {@code null} when it addresses
   *     none
   * @param permission What the request does with it, or {@code null} when it needs no grant
   * @return The {@code WWW-Authenticate} header value
   */
  static String of(
      final HttpServletRequest request,
      final @Nullable String name,
      final @Nullable Permission permission) {

    final var baseUrl = RequestBaseUrlUtils.resolveBaseUrl(request);
    final var actions = actionsOf(permission);

    if (StringUtils.isBlank(name) || actions == null) {
      return "Bearer realm=\"%s/v2/token\",service=\"repsy\"".formatted(baseUrl);
    }

    return "Bearer realm=\"%s/v2/token\",service=\"repsy\",scope=\"repository:%s:%s\""
        .formatted(baseUrl, name, actions);
  }

  /**
   * Builds the challenge of a request from what its route says: the image its path addresses and
   * the permission its handler declares.
   *
   * @param context The context of the request
   * @param request The unauthenticated or rejected request
   * @param properties The properties of the handler that serves the request
   * @return The {@code WWW-Authenticate} header value
   */
  static String of(
      final ProtocolContext context,
      final HttpServletRequest request,
      final Map<String, Object> properties) {

    return of(
        request,
        requestedName(context, ProtocolContextUtils.getRepoInfo(context)),
        (Permission) properties.get("permission"));
  }

  /**
   * The {@code <repo>/<image>} a request addresses: its path is {@code /<image>/manifests/...}.
   *
   * @return The name, or {@code null} for a request that addresses no image, the registry ping
   */
  static @Nullable String requestedName(final ProtocolContext context, final RepoInfo repoInfo) {

    final var segments =
        StringUtils.split(ProtocolContextUtils.getRelativePath(context).getPath(), '/');

    if (StringUtils.isBlank(repoInfo.getName()) || segments == null || segments.length == 0) {
      return null;
    }

    return repoInfo.getName() + "/" + segments[0];
  }

  /** The {@code scope} actions a permission asks for: a push also pulls, as {@code docker} asks. */
  private static @Nullable String actionsOf(final @Nullable Permission permission) {

    return switch (permission) {
      case READ -> "pull";
      case WRITE -> "pull,push";
      case MANAGE -> "delete";
      case null, default -> null;
    };
  }

  /**
   * Builds the challenge for a request whose token was valid but not issued for the operation, so
   * that the client asks {@code /v2/token} again for {@code scope} (the distribution spec's {@code
   * insufficient_scope}) instead of retrying with the same token.
   *
   * @param request The rejected request
   * @param scope The scope the operation needs, for example {@code repository:repo/app:delete}
   * @return The {@code WWW-Authenticate} header value
   */
  static String insufficientScope(final HttpServletRequest request, final String scope) {

    return "Bearer realm=\"%s/v2/token\",service=\"repsy\",scope=\"%s\",error=\"insufficient_scope\""
        .formatted(RequestBaseUrlUtils.resolveBaseUrl(request), scope);
  }
}
