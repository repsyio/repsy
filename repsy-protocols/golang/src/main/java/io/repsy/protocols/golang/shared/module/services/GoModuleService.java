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
package io.repsy.protocols.golang.shared.module.services;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface GoModuleService<ID> {

  /**
   * Atomically creates (or reuses) the module record and creates a new version record with
   * PUBLISHED status. Throws {@link
   * io.repsy.core.error_handling.exceptions.ItemAlreadyExistException} if the version already
   * exists.
   */
  void publishModule(
      BaseRepoInfo<ID> repoInfo,
      String modulePath,
      String version,
      @Nullable String goVersion,
      String modHash,
      String zipHash);

  /**
   * Returns the version string of the most recently uploaded PUBLISHED version for the given
   * module, ordered by creation time descending.
   */
  Optional<String> findLatestPublishedVersion(BaseRepoInfo<ID> repoInfo, String modulePath);
}
