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
package io.repsy.protocols.shared.utils;

import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import lombok.Data;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
public class BaseUrlParserProperties<ID, T extends BaseRepoInfo<ID>> {
  private final String repoName;
  private final RelativePath relativePath;
  private final T repoInfo;

  /**
   * The repository as the request URL names it, without the image: {@code <repo>} here, and {@code
   * <owner>/<repo>} in a registry whose repositories live under an owner (Repsy Cloud overrides
   * it). The distribution spec wants the {@code name} of a {@code tags/list} answer to be what the
   * client addressed, so it is built from this and not from {@link #getRepoName()}.
   */
  public String getRepoPath() {
    return this.repoName;
  }
}
