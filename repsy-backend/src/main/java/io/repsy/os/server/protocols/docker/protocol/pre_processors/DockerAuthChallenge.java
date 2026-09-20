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

import io.repsy.os.server.shared.utils.RequestBaseUrlUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * The {@code WWW-Authenticate} challenge of the Docker registry: a Bearer realm that points the
 * client to the token endpoint. It answers a request without credentials as well as one whose
 * credentials were rejected.
 */
@UtilityClass
@NullMarked
final class DockerAuthChallenge {

  /**
   * Builds the challenge for a request, with the token endpoint of the host the client called.
   *
   * @param request The unauthenticated or rejected request
   * @return The {@code WWW-Authenticate} header value
   */
  static String of(final HttpServletRequest request) {

    return "Bearer realm=\"%s/v2/token\",service=\"repsy\",scope=\"repository:*:pull\""
        .formatted(RequestBaseUrlUtils.resolveBaseUrl(request));
  }
}
