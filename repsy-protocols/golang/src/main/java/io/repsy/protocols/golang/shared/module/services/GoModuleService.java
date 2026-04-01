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
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public interface GoModuleService<ID> {

  /**
   * Creates a new module record if it does not exist, then creates a version record if it does not
   * already exist. Updates the module's latest version pointer.
   */
  void createOrUpdateModule(BaseRepoInfo<ID> repoInfo, String modulePath, String version);

  /**
   * Sets the go toolchain version (from go.mod "go X.Y" directive) on an existing version record.
   * No-op if the version record is not found.
   */
  void updateGoVersion(
      BaseRepoInfo<ID> repoInfo, String modulePath, String version, @Nullable String goVersion);
}
