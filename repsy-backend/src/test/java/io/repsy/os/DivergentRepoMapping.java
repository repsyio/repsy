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

import jakarta.persistence.Column;
import jakarta.persistence.Table;

/**
 * Not an entity: a deliberately wrong mapping of the {@code repo} table, used to prove that {@link
 * EntityColumnSchemaChecks} reports a divergence instead of passing on everything.
 */
@Table(name = "repo")
@SuppressWarnings("unused")
final class DivergentRepoMapping {

  /** The column is varchar(25). */
  @Column(name = "name", nullable = false, length = 26)
  private String name;

  /** The column is nullable. */
  @Column(name = "description", nullable = false, length = 500)
  private String description;

  /** The column is NOT NULL, and a wrapper type does not imply it. */
  @Column(name = "searchable")
  private Boolean searchable;

  /** The column is varchar(20), not text. */
  @Column(name = "type", columnDefinition = "text")
  private String type;

  /** The table has no such column. */
  @Column(name = "no_such_column")
  private String missing;

  /** A correct mapping, which must not be reported. */
  @Column(name = "disk_usage", nullable = false)
  private long diskUsage;
}
