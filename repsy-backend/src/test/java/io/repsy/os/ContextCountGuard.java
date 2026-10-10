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
package io.repsy.os;

import io.repsy.libs.testsupport.AbstractContextCountGuard;

/**
 * The OS side of {@link AbstractContextCountGuard}: every context of a run keeps its own connection
 * pool to the one PostgreSQL container that all {@link AbstractIT} classes share (see the Hikari
 * settings there).
 */
public final class ContextCountGuard extends AbstractContextCountGuard {

  /**
   * Distinct context configurations a run may use; a full run of the classes that extend the base
   * used 33 when this was added.
   */
  static final int MAX_CONTEXTS = 38;

  @Override
  protected int maxContexts() {
    return MAX_CONTEXTS;
  }
}
