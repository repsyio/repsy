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
package io.repsy.protocols.npm.shared.npm_package.dtos;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * What the database knows of a package, as the input for rebuilding its metadata file ({@code
 * package.json}) when that file is gone from storage.
 *
 * <p>The rows hold only part of what a published version's metadata says: the name, version,
 * description, homepage, license, repository, author, bugs, keywords, maintainers, the deprecation
 * and the dist-tags. They do not hold the dependencies, scripts, {@code dist}, readme or anything
 * else the client sent, so a rebuild takes those from the tarball where it can.
 *
 * @param scope the scope without the leading {@code @}, or {@code null}
 * @param name the package name without the scope
 * @param latest the version {@code latest} points at
 * @param versions the versions, oldest first
 * @param distTags the dist-tags, as tag to version name
 */
@NullMarked
public record NpmPackageSnapshot(
    @Nullable String scope,
    String name,
    @Nullable String latest,
    Instant createdAt,
    List<Version> versions,
    Map<String, String> distTags) {

  /**
   * One version of the package.
   *
   * @param deprecation the deprecation message, or {@code null} when the version is not deprecated
   */
  public record Version(
      String version,
      Instant createdAt,
      @Nullable String description,
      @Nullable String homepage,
      @Nullable String license,
      @Nullable String repositoryType,
      @Nullable String repositoryUrl,
      @Nullable String authorName,
      @Nullable String authorEmail,
      @Nullable String authorUrl,
      @Nullable String bugsUrl,
      @Nullable String bugsEmail,
      @Nullable String deprecation,
      List<String> keywords,
      List<Maintainer> maintainers) {}

  /** A maintainer of a version. */
  public record Maintainer(@Nullable String name, @Nullable String email, @Nullable String url) {}
}
