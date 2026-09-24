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
package io.repsy.protocols.npm.shared.npm_package.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageInfo;
import io.repsy.protocols.npm.shared.npm_package.dtos.PackageDistributionTagMapListItem;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.util.Pair;

public interface NpmPackageService<ID> {

  /**
   * Records the published version and, while that write is still open, stores its files through
   * {@code writer}.
   *
   * <p>The rows are written first (and flushed, so a unique-index conflict surfaces here) and the
   * files second, inside one transaction that holds the package row locked. So a publish that the
   * database rejects never touches storage, a concurrent publish of the same package waits for the
   * winner instead of replacing its files or the package metadata it just wrote, and a publish
   * whose files cannot be written leaves no rows behind.
   *
   * @param payload the publish body as the client sent it; {@code writer} may still change it
   * @return the usages reported by {@code writer}
   * @throws io.repsy.core.error_handling.exceptions.AccessNotAllowedException when the version
   *     exists and the repo does not allow overrides
   */
  @NonNull BaseUsages publishVersion(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String versionName,
      @NonNull Map<String, Object> payload,
      @NonNull VersionWriter writer)
      throws IOException, URISyntaxException;

  /** What a publish does to the rows, and so to the files {@link VersionWriter} has to write. */
  enum PublishKind {
    /** The package did not exist: its first version, and the package itself, are new. */
    NEW_PACKAGE,
    /** The package existed and gains a version. */
    NEW_VERSION,
    /** The version already existed and is being replaced. */
    REPLACES_VERSION
  }

  /** Stores the files of a version whose rows {@link #publishVersion} has just written. */
  @FunctionalInterface
  interface VersionWriter {

    /**
     * Writes the files of the version.
     *
     * @param kind what the publish adds. A writer that fails must not delete files it did not
     *     create, so it leaves the files of {@link PublishKind#REPLACES_VERSION} alone.
     */
    @NonNull BaseUsages write(@NonNull PublishKind kind) throws IOException, URISyntaxException;
  }

  BasePackageInfo<ID> getPackage(
      UUID storageKey, @Nullable String scopeName, @NonNull String packageName);

  /** The names of the versions the package has. */
  @NonNull List<String> getVersionNames(ID packageId);

  /**
   * Deletes the package and its versions and, while that write is still open, removes its files
   * through {@code remover}.
   *
   * <p>Like {@link #publishVersion}, the package row is locked first, the rows are deleted and
   * flushed second, and the files are removed third, all in one transaction. So a delete the
   * database rejects never touches storage, a publish of the package waits for the delete instead
   * of writing into a directory that is being removed, and files that cannot be removed roll the
   * rows back.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package does not
   *     exist
   */
  @NonNull PackageDeletion deletePackage(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull PackageRemover remover);

  /**
   * Deletes the version, and the package with it when that was its last version, and while that
   * write is still open removes the files through {@code versionRemover} or {@code packageRemover},
   * in the same way as {@link #deletePackage}.
   *
   * <p>When the version was the latest, the row of the {@code latest} tag and the package's latest
   * move to the highest remaining version before the files are touched, and that version is what
   * {@link VersionRemover#removeVersion} is told.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package or the
   *     version does not exist
   */
  @NonNull PackageDeletion deletePackageVersion(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String versionName,
      @NonNull VersionRemover versionRemover,
      @NonNull PackageRemover packageRemover)
      throws IOException;

  /**
   * What a delete removed: the names of the versions that are gone, and the usages that {@link
   * PackageRemover} or {@link VersionRemover} reported.
   */
  record PackageDeletion(@NonNull List<String> versions, @NonNull BaseUsages usages) {}

  /** Removes the files of a package whose rows {@link #deletePackage} has just deleted. */
  @FunctionalInterface
  interface PackageRemover {

    /**
     * Removes every file of the package. Nothing is put back when this fails afterwards, so it is
     * the last thing that runs in the transaction.
     */
    @NonNull BaseUsages removePackage();
  }

  /** Removes the files of a version whose rows {@link #deletePackageVersion} has just deleted. */
  @FunctionalInterface
  interface VersionRemover {

    /**
     * Rewrites the package metadata and removes the tarball, the tarball last. A remover that fails
     * must put back the metadata it found, so the rows that are rolled back with the failure and
     * the metadata keep agreeing. A tarball that was removed cannot be put back, which is why it is
     * the last thing to go.
     *
     * @param newLatest the version {@code latest} moved to, or {@code null} when it did not move
     */
    @NonNull BaseUsages removeVersion(@Nullable String newLatest) throws IOException;
  }

  /**
   * Sets or clears the deprecation of the versions and, while that write is still open, stores the
   * package metadata through {@code writer}, in the same way as {@link #addDistributionTag}.
   *
   * @param deprecations pairs of version and message; an empty message removes the deprecation
   * @return the usages reported by {@code writer}
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package or a
   *     version does not exist
   */
  @NonNull BaseUsages handleDeprecations(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull List<Pair<String, String>> deprecations,
      @NonNull MetadataWriter writer)
      throws IOException;

  @NonNull List<PackageDistributionTagMapListItem> getDistributionTags(ID id);

  /**
   * Points {@code tagName} at {@code versionName} and, while that write is still open, stores the
   * package metadata through {@code writer}.
   *
   * <p>Like {@link #publishVersion}, the package row is locked first, the tag row is written and
   * flushed second, and the metadata is written third, all in one transaction. So a tag change the
   * database rejects never touches storage, it cannot lose an update against a concurrent publish
   * of the package (both read-modify-write the one metadata file, and the lock makes them run one
   * after another), and a metadata write that fails rolls the tag row back.
   *
   * @return the usages reported by {@code writer}
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package does not
   *     exist
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException when the version does not
   *     exist
   */
  @NonNull BaseUsages addDistributionTag(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String tagName,
      @NonNull String versionName,
      @NonNull MetadataWriter writer)
      throws IOException;

  /**
   * Removes {@code tagName} and, while that write is still open, stores the package metadata
   * through {@code writer}, in the same way as {@link #addDistributionTag}.
   *
   * @return the usages reported by {@code writer}
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException when the package does not
   *     exist
   */
  @NonNull BaseUsages removeDistributionTag(
      @NonNull BaseRepoInfo<ID> repoInfo,
      @Nullable String scopeName,
      @NonNull String packageName,
      @NonNull String tagName,
      @NonNull MetadataWriter writer)
      throws IOException;

  /** Rewrites the package metadata of a dist-tag change the rows of which are already written. */
  @FunctionalInterface
  interface MetadataWriter {

    /**
     * Writes the package metadata. A writer that fails must put back the metadata it found, so the
     * rows that are rolled back with the failure and the metadata keep agreeing.
     */
    @NonNull BaseUsages write() throws IOException;
  }
}
