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
package io.repsy.protocols.docker.shared.auth.services;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface DockerAuthService<ID> {

  /**
   * Checks that a caller without credentials may read the repo, which is what {@code /v2/token}
   * asks before it hands out an anonymous token. Only a public repo passes; a private one is
   * answered as if it did not exist.
   */
  void authorizePublicRead(BaseRepoInfo<ID> repoInfo);

  String createAnonymousUser();

  /**
   * Exchanges Basic credentials for a token.
   *
   * @param authHeader The Basic {@code Authorization} header
   * @param grants What the client asked for, as {@link
   *     io.repsy.protocols.docker.protocol.parser.DockerScopes#parseGrants} reads it. The token
   *     records them, so a request it authorizes can be checked against them (RPS-1434). The
   *     exchange itself is not refused for a scope: authorization stays per request.
   */
  String authenticateUserDockerCli(String authHeader, List<String> grants);
}
