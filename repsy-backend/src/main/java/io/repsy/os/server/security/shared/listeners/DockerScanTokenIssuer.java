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
package io.repsy.os.server.security.shared.listeners;

import io.repsy.os.shared.auth.utils.JwtUtils;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Mints short-lived, single-repo, read-only registry pull tokens for the vulnerability scanner to
 * use against a private Docker repo. Deliberately package-private (class and method): the only
 * caller is {@link ArtifactScanListener}'s Docker branch, in this same package — no controller or
 * other externally-reachable code path can reference this type at all, so this scoped-token
 * capability cannot be triggered by anything other than an actual artifact-pushed event.
 */
@Component
@RequiredArgsConstructor
class DockerScanTokenIssuer {

  private static final @NonNull Duration TOKEN_DURATION = Duration.ofMinutes(5);
  private static final @NonNull String PULL_SCOPE_TEMPLATE = "repository:%s:pull";

  private final @NonNull JwtUtils jwtUtils;

  @NonNull String mintReadOnlyPullToken(
      final @NonNull UUID repoId, final @NonNull String repoName) {
    final var scope = PULL_SCOPE_TEMPLATE.formatted(repoName);

    return this.jwtUtils.createRepoScopedToken(repoId, scope, TOKEN_DURATION);
  }
}
