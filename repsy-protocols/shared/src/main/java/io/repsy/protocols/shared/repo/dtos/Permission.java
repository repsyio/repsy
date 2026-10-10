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
package io.repsy.protocols.shared.repo.dtos;

import java.util.List;

public enum Permission {
  WRITE,
  READ,
  MANAGE,
  NONE;

  private static final List<Permission> LADDER = List.of(NONE, READ, WRITE, MANAGE);

  /**
   * Whether this permission is at least as strong as {@code other}. The permissions are a ladder,
   * {@link #NONE} below {@link #READ} below {@link #WRITE} below {@link #MANAGE}; the declaration
   * order is not the ladder and must not be used for comparing.
   */
  public boolean isAtLeast(final Permission other) {
    return LADDER.indexOf(this) >= LADDER.indexOf(other);
  }
}
