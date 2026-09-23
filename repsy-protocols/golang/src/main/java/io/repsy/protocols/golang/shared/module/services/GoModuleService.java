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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface GoModuleService<I> {

  /**
   * Records a new module version and, while that row is still uncommitted, has {@code filesWriter}
   * store its files (RPS-1124). The row is written and flushed first, so an upload that cannot
   * write it never touches storage and the loser of a race for the same version is turned away
   * before it can replace the winner's files. The transaction stays open while the files are
   * written, so a failure of any of them rolls the row back too and storage and the database agree.
   *
   * @return the usages reported by {@code filesWriter}
   * @throws io.repsy.core.error_handling.exceptions.ItemAlreadyExistException when the version
   *     exists
   */
  BaseUsages publishModule(
      BaseRepoInfo<I> repoInfo,
      String modulePath,
      String version,
      @Nullable String goVersion,
      String modHash,
      String zipHash,
      GoModuleFilesWriter filesWriter)
      throws IOException;

  Optional<String> findLatestPublishedVersion(BaseRepoInfo<I> repoInfo, String modulePath);
}
