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
package io.repsy.protocols.npm.shared.auth.services;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Tells who a request's credentials belong to, for {@code GET /-/whoami}. It is a separate
 * interface, and not a method of {@link NpmAuthComponent}, so that an implementation of that
 * component outside this repository keeps compiling.
 */
@NullMarked
public interface NpmIdentityResolver<ID> {

  /**
   * Verifies the {@code Authorization} header against the repository and returns the username it
   * stands for. A deploy token stands for its own generated username, never for the name the client
   * typed.
   *
   * @param repoInfo The repository the request is for
   * @param authHeader The raw {@code Authorization} header, or {@code null} if the request has none
   * @return The username, never blank
   * @throws io.repsy.core.error_handling.exceptions.UnAuthorizedException If the header is missing,
   *     unsupported, invalid, expired or belongs to another repository
   */
  String resolveUsername(BaseRepoInfo<ID> repoInfo, @Nullable String authHeader);
}
