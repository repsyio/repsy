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
package io.repsy.protocols.docker.protocol.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code scope} parameters of {@code /v2/token} and answers what a token that was issued
 * for them may do (RPS-1434). A grant is the string {@code <name>:<action>[,<action>...]} with a
 * lower-case name, for example {@code repo/app:pull,push}; it is what the token carries.
 */
@UtilityClass
@NullMarked
public final class DockerScopes {

  private static final String REPOSITORY_TYPE = "repository:";
  private static final String DELETE_ACTION = "delete";
  private static final String ALL_ACTIONS = "*";

  /**
   * Turns the {@code scope} parameters of a token request into grants. Each parameter may hold
   * several space separated scopes, and a client that mounts a blob from another repo sends more
   * than one parameter. Only {@code repository} scopes are kept; anything else names no repo.
   *
   * @param scopeParameters The values of the {@code scope} parameter, or {@code null} when absent
   * @return The grants, without duplicates, in request order
   */
  public static List<String> parseGrants(final String @Nullable [] scopeParameters) {

    final var grants = new LinkedHashSet<String>();

    if (scopeParameters == null) {
      return List.of();
    }

    for (final var parameter : scopeParameters) {
      for (final var scope : StringUtils.split(parameter)) {
        final var grant = toGrant(scope);

        if (grant != null) {
          grants.add(grant);
        }
      }
    }

    return new ArrayList<>(grants);
  }

  /**
   * Tells whether the grants allow deleting in {@code name}, which is {@code <repo>/<image>}. Only
   * a grant of exactly that name counts, and only with the {@code delete} action or {@code *}: a
   * token issued for {@code pull} or {@code push,pull} cannot delete.
   */
  public static boolean allowsDelete(final List<String> grants, final String name) {

    final var wanted = name.toLowerCase(Locale.ROOT);

    for (final var grant : grants) {
      final var actionsStart = grant.lastIndexOf(':');

      if (actionsStart < 0 || !grant.substring(0, actionsStart).equals(wanted)) {
        continue;
      }

      final var actions = Arrays.asList(StringUtils.split(grant.substring(actionsStart + 1), ','));

      if (actions.contains(DELETE_ACTION) || actions.contains(ALL_ACTIONS)) {
        return true;
      }
    }

    return false;
  }

  private static @Nullable String toGrant(final String scope) {

    if (!scope.regionMatches(true, 0, REPOSITORY_TYPE, 0, REPOSITORY_TYPE.length())) {
      return null;
    }

    final var rest = scope.substring(REPOSITORY_TYPE.length());
    final var actionsStart = rest.lastIndexOf(':');

    if (actionsStart <= 0) {
      return null;
    }

    final var actions = new LinkedHashSet<String>();

    for (final var action : StringUtils.split(rest.substring(actionsStart + 1), ',')) {
      final var trimmed = action.trim().toLowerCase(Locale.ROOT);

      if (!trimmed.isEmpty()) {
        actions.add(trimmed);
      }
    }

    if (actions.isEmpty()) {
      return null;
    }

    return rest.substring(0, actionsStart).toLowerCase(Locale.ROOT)
        + ":"
        + String.join(",", actions);
  }
}
