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
package io.repsy.os.server.protocols.docker.shared.auth.services;

import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

/**
 * Narrows what a Docker client asked for at {@code /v2/token} to what a personal access token may
 * do (RPS-1903). A grant is {@code <name>:<action>[,<action>...]} (see {@code DockerScopes}); the
 * token that is issued carries the narrowed grants, the intersection of three things: what was
 * asked for, the scopes of the token, and what its owner may do.
 *
 * <p>An action is kept only if the permission it needs is granted: {@code pull} needs read, {@code
 * push} needs write and {@code delete} needs manage, which is the permission of an ADMIN, so a
 * {@code repo:manage} token of a user who is not one is not granted it. {@code *} asks for every
 * action and is answered with the ones that are granted, spelled out. An action that is not one of
 * these three is never granted. A grant left without an action is dropped.
 */
@UtilityClass
final class PatDockerGrants {

  private static final String ALL_ACTIONS = "*";

  /**
   * @param grants what the client asked for
   * @param scopes the scopes of the token
   * @param admin whether the owner of the token is an ADMIN
   * @return the grants the token may be issued with, in the order they were asked for
   */
  static List<String> narrow(
      final List<String> grants, final Collection<TokenScope> scopes, final boolean admin) {

    final var narrowed = new ArrayList<String>();

    for (final var grant : grants) {
      final var actionsStart = grant.lastIndexOf(':');

      if (actionsStart < 0) {
        continue;
      }

      final var actions = allowedActions(grant.substring(actionsStart + 1), scopes, admin);

      if (!actions.isEmpty()) {
        narrowed.add(grant.substring(0, actionsStart) + ":" + String.join(",", actions));
      }
    }

    return narrowed;
  }

  private static List<String> allowedActions(
      final String actions, final Collection<TokenScope> scopes, final boolean admin) {

    final var allowed = new ArrayList<String>();

    for (final var action : List.of("pull", "push", "delete")) {
      if (asked(actions, action) && granted(action, scopes, admin)) {
        allowed.add(action);
      }
    }

    return allowed;
  }

  private static boolean asked(final String actions, final String action) {

    for (final var asked : StringUtils.split(actions, ',')) {
      if (asked.equals(action) || asked.equals(ALL_ACTIONS)) {
        return true;
      }
    }

    return false;
  }

  private static boolean granted(
      final String action, final Collection<TokenScope> scopes, final boolean admin) {

    return switch (action) {
      case "pull" -> TokenScope.permits(scopes, Permission.READ);
      case "push" -> TokenScope.permits(scopes, Permission.WRITE);
      default -> admin && TokenScope.permits(scopes, Permission.MANAGE);
    };
  }
}
