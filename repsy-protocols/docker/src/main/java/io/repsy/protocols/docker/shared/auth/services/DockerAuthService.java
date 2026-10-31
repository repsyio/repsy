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

public interface DockerAuthService<I> {

  /**
   * Checks that a caller without credentials may read the repo, which is what {@code /v2/token}
   * asks before it hands out an anonymous token. Only a public repo passes; a private one is
   * answered as if it did not exist.
   */
  void authorizePublicRead(BaseRepoInfo<I> repoInfo);

  /**
   * Creates the token of a caller without credentials.
   *
   * @param grants What the client asked for. The token is granted {@code pull} of them and nothing
   *     more (RPS-2216)
   */
  String createAnonymousUser(List<String> grants);

  /**
   * Exchanges Basic credentials for a token.
   *
   * @param authHeader The Basic {@code Authorization} header
   * @param grants What the client asked for, as {@link
   *     io.repsy.protocols.docker.protocol.parser.DockerScopes#parseGrants} reads it. The token
   *     records the part of them the credential may exercise, the intersection of what was asked
   *     for and what it is permitted (RPS-2216). The exchange itself is not refused for a scope: a
   *     token that leaves nothing has an empty access list, and the registry refuses the operation
   *     (the Docker token specification), so {@code docker login} keeps working.
   */
  String authenticateUserDockerCli(String authHeader, List<String> grants);
}
