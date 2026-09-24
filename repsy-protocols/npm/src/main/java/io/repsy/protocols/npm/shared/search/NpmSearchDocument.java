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
package io.repsy.protocols.npm.shared.search;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * The searchable facts of a package: its name and what its latest version says about it.
 *
 * @param scope The scope without the {@code @}, or {@code null} for an unscoped package
 * @param name The package name without its scope
 * @param version The latest version
 * @param keywords The keywords of the latest version
 * @param date When the latest version was published
 */
@NullMarked
public record NpmSearchDocument(
    @Nullable String scope,
    String name,
    String version,
    @Nullable String description,
    List<String> keywords,
    @Nullable Instant date,
    @Nullable String homepage,
    @Nullable String repositoryUrl,
    @Nullable String bugsUrl,
    @Nullable String authorName,
    @Nullable String authorEmail,
    @Nullable String authorUrl) {

  /** The package name with its scope as npm writes it: {@code @scope/name} or {@code name}. */
  public String fullName() {
    return this.scope == null ? this.name : "@" + this.scope + "/" + this.name;
  }

  /** The lowercase {@code scope/name} (or {@code name}) that free search terms are matched on. */
  String key() {
    return (this.scope == null ? this.name : this.scope + "/" + this.name).toLowerCase(Locale.ROOT);
  }
}
