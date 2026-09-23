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
package io.repsy.protocols.ruby.shared.gem.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.ruby.shared.gem.dtos.GemVersionsEntry;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface RubyGemProtocolService<ID> {

  List<String> getGemNames(BaseRepoInfo<ID> repoInfo);

  List<GemCompactEntry> getCompactEntriesByGemName(BaseRepoInfo<ID> repoInfo, String gemName);

  /**
   * Resolves a {@code .gem} filename against the stored rows, trying each of {@link
   * io.repsy.protocols.ruby.shared.utils.GemFilenameCandidates#split candidate} readings
   * longest-name first and returning the first that matches a real gem and version. Unlike {@link
   * #getCompactEntriesByGemName}, this never throws for an unresolved filename.
   */
  Optional<GemCompactEntry> findByGemFilename(BaseRepoInfo<ID> repoInfo, String filename);

  /** Cheap existence check for {@code /info/<gemName>}: whether the gem has any row at all. */
  boolean gemNameExists(BaseRepoInfo<ID> repoInfo, String gemName);

  /**
   * Cheap existence check for a gemspec: whether the gem has a non-yanked row at {@code version}.
   */
  boolean hasNonYankedVersion(BaseRepoInfo<ID> repoInfo, String gemName, String version);

  /**
   * Records the gem version and, while that write is still open, stores its file through {@code
   * fileWriter}.
   *
   * <p>The row is written first (and flushed, so a unique-index conflict surfaces here) and the
   * file second, inside one transaction. If the row cannot be written, the file is never touched,
   * so a publish that loses a race for a version cannot replace the winner's file. If the file
   * cannot be written, the row is rolled back.
   *
   * @return the usages reported by {@code fileWriter}
   */
  BaseUsages publishGem(
      BaseRepoInfo<ID> repoInfo, GemMetadata metadata, String checksum, GemFileWriter fileWriter)
      throws IOException;

  void yankGem(BaseRepoInfo<ID> repoInfo, String gemName, String version, String platform);

  Map<String, GemVersionsEntry> getVersionsChecksums(BaseRepoInfo<ID> repoInfo);

  void saveVersionsChecksum(BaseRepoInfo<ID> repoInfo, String gemName, String checksum);

  List<GemCompactEntry> getAllNonYankedEntries(BaseRepoInfo<ID> repoInfo);

  /** Stores the file of a version whose row {@link #publishGem} has just written. */
  @FunctionalInterface
  interface GemFileWriter {

    /**
     * Writes the file of the version.
     *
     * @param replacesExisting whether the version already had a row and a file, which are being
     *     replaced. A writer that fails must not delete a file it did not create.
     */
    BaseUsages write(boolean replacesExisting) throws IOException;
  }
}
