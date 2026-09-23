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
package io.repsy.os.shared.repo.utils;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

@UtilityClass
public class RepoUtils {
  private static final @NonNull Pattern REPO_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

  /**
   * Repository names that would collide with a fixed top-level route the panel declares ahead of
   * its {@code :repoName} route ({@code app.routes.ts}: {@code login}, {@code profile}, {@code
   * repositories}, {@code users}, {@code security}, {@code not-found}), plus the paths the API port
   * forwards to the SPA before anything more specific can claim them ({@code api}, {@code assets},
   * {@code favicon.ico}). Compared case-insensitively. Keep this in sync with {@code
   * app.routes.ts}, which points back here in a comment.
   */
  private static final @NonNull Set<String> RESERVED_REPO_NAMES =
      Set.of(
          "login",
          "profile",
          "repositories",
          "users",
          "security",
          "not-found",
          "api",
          "assets",
          "favicon.ico");

  public void validateRepoName(final @NonNull String repoName) {
    if (!REPO_NAME_PATTERN.matcher(repoName).matches()) {
      throw new AccessNotAllowedException("invalidRequest");
    }
  }

  /**
   * Validates a name a caller is newly choosing for a repository (create, rename): the character
   * set {@link #validateRepoName} checks, plus that it is not a {@linkplain #RESERVED_REPO_NAMES
   * reserved name}.
   *
   * <p>Deliberately not folded into {@link #validateRepoName}: that method also runs on every
   * access to an <em>existing</em> repo (as a path-traversal guard in protocol facades), and a
   * repository that already carries a reserved name from before this check existed must keep being
   * reachable there, not just left un-renameable.
   */
  public void validateNewRepoName(final @NonNull String repoName) {
    RepoUtils.validateRepoName(repoName);

    if (RESERVED_REPO_NAMES.contains(repoName.toLowerCase(Locale.ROOT))) {
      throw new BadRequestException("repoNameReserved");
    }
  }
}
