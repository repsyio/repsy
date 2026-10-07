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
package io.repsy.os.shared.token.dtos;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.repsy.protocols.shared.repo.dtos.Permission;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * What a personal access token may do. A token holds a set of these and can only narrow what its
 * owner can do, never widen it: the permission it acts with is the owner's own permission
 * intersected with the repo scopes below, and {@link #REPO_MANAGE} is never implied by another
 * scope.
 *
 * <p><b>The declaration order is the canonical order</b>, and it is the alphabetical order of the
 * wire values. {@link EnumSet} iterates in declaration order, so a set of scopes is written to the
 * database, and to JSON, in one fixed order whichever order it was built in. {@code TokenScopeTest}
 * fails when a constant is added out of order.
 *
 * <p>This enum is also the {@code AccessTokenScope} schema of the OpenAPI spec (a {@code
 * schemaMapping} in the {@code pom.xml}), so the wire values are the ones in {@link #getValue()}.
 */
public enum TokenScope {
  /** Reading who the token belongs to. Every token has it, so it is never asked for. */
  PROFILE_READ("profile:read", Permission.NONE),
  /** Deleting packages and versions, and changing a repo's settings. Implies write and read. */
  REPO_MANAGE("repo:manage", Permission.MANAGE),
  /** Downloading, and reading metadata and lists. */
  REPO_READ("repo:read", Permission.READ),
  /** Publishing. Implies read. */
  REPO_WRITE("repo:write", Permission.WRITE),
  /** Reading the results of security scans. */
  SCAN_READ("scan:read", Permission.NONE);

  /** The permissions from the lowest to the highest, which {@link #grants} compares by position. */
  private static final List<Permission> LADDER =
      List.of(Permission.NONE, Permission.READ, Permission.WRITE, Permission.MANAGE);

  private final @NonNull String value;
  private final @NonNull Permission permission;

  TokenScope(final @NonNull String value, final @NonNull Permission permission) {
    this.value = value;
    this.permission = permission;
  }

  @JsonValue
  public @NonNull String getValue() {
    return this.value;
  }

  /**
   * The highest repo permission this scope grants on its own, or {@link Permission#NONE} for a
   * scope that is not about repos.
   */
  public @NonNull Permission getPermission() {
    return this.permission;
  }

  /** The scope whose wire value is {@code value}, exactly; there is no case folding. */
  public static @NonNull Optional<TokenScope> fromValue(final String value) {
    for (final var scope : values()) {
      if (scope.value.equals(value)) {
        return Optional.of(scope);
      }
    }

    return Optional.empty();
  }

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static @NonNull TokenScope fromJson(final String value) {
    return fromValue(value)
        .orElseThrow(() -> new IllegalArgumentException("Unknown token scope: " + value));
  }

  /**
   * The scopes a token is created with: the requested ones plus the implicit {@link #PROFILE_READ},
   * as a set in canonical order.
   */
  public static @NonNull EnumSet<TokenScope> withImplicit(
      final @NonNull Collection<TokenScope> requested) {
    final var scopes = EnumSet.of(PROFILE_READ);

    scopes.addAll(requested);

    return scopes;
  }

  /**
   * Whether this scope grants {@code required}. The permissions are a ladder, {@link
   * Permission#NONE} below {@link Permission#READ} below {@link Permission#WRITE} below {@link
   * Permission#MANAGE}, and a scope grants its own rung and every one below it: a manage scope
   * grants write and read, a write scope grants read, and a scope that is not about repos grants
   * only {@link Permission#NONE}, which asks for nothing.
   */
  public boolean grants(final @NonNull Permission required) {
    return LADDER.indexOf(required) <= LADDER.indexOf(this.permission);
  }

  /**
   * Whether {@code scopes} grant {@code required}: any one of them does (see {@link #grants}), and
   * {@link Permission#NONE} asks for nothing, so even no scope at all grants it.
   */
  public static boolean permits(
      final @NonNull Collection<TokenScope> scopes, final @NonNull Permission required) {
    return required == Permission.NONE || scopes.stream().anyMatch(scope -> scope.grants(required));
  }
}
