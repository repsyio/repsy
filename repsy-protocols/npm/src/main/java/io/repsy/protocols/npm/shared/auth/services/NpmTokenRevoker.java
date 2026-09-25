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
 * Revokes a login token, for {@code DELETE /-/user/token/<token>}, which {@code npm logout} and
 * {@code pnpm logout} call. It is a separate interface, and not a method of {@link
 * NpmAuthComponent}, so that an implementation of that component outside this repository keeps
 * compiling.
 */
@NullMarked
public interface NpmTokenRevoker<ID> {

  /**
   * Revokes {@code token}, a token the login of this registry issued, so that it is refused from
   * now on. The caller has to prove who it is with {@code authHeader}, and may only revoke a token
   * of its own: the token it presents itself, or another one issued to the same user (or to the
   * same deploy token). The secret of a deploy token is not a login token and is never revoked
   * here.
   *
   * @param repoInfo The repository the request is for
   * @param authHeader The raw {@code Authorization} header, or {@code null} if the request has none
   * @param token The token to revoke, as it was sent in the path
   * @throws io.repsy.core.error_handling.exceptions.UnAuthorizedException If the header is missing,
   *     unsupported, invalid, expired or belongs to another repository
   * @throws io.repsy.core.error_handling.exceptions.AccessNotAllowedException If the token belongs
   *     to somebody else, or is the secret of a deploy token
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException If the token is none this
   *     registry issued, or it has expired already
   */
  void revokeToken(BaseRepoInfo<ID> repoInfo, @Nullable String authHeader, String token);
}
