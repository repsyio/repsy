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
package io.repsy.protocols.pypi.shared.python_package.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.protocols.pypi.shared.python_package.dtos.BasePackageInfo;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import io.repsy.protocols.pypi.shared.python_package.dtos.ReleaseVersionRequiresPython;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface PypiPackageService<ID> {

  /**
   * Records the release of the upload and, while that write is still open, stores its archive
   * through {@code fileWriter}.
   *
   * <p>The package and release rows are written first (and flushed, so a database rejection
   * surfaces here) and the archive second, inside one transaction that holds the package's row
   * lock. So an upload whose rows cannot be written never touches storage, an upload that fails
   * while writing the archive leaves no row behind, and two uploads of the same package take turns:
   * the second one runs its writer only after the first one has committed, and so sees the first
   * one's file.
   *
   * @return the usages reported by {@code fileWriter}
   */
  BaseUsages publishRelease(
      BaseRepoInfo<ID> repoInfo, PackageUploadForm uploadForm, ReleaseFileWriter fileWriter)
      throws IOException;

  BasePackageInfo<ID> getPackage(ID repoId, String packageNormalizedName);

  /** Existence-only check, never {@link #getPackage}: used to answer {@code HEAD}. */
  boolean packageExists(ID repoId, String packageNormalizedName);

  List<ReleaseVersionRequiresPython> getReleaseIndexListItemInfos(ID packageId);

  /** Stores the archive of a release whose rows {@link #publishRelease} has just written. */
  @FunctionalInterface
  interface ReleaseFileWriter {

    /** Writes the archive file, and its digest, of the release. */
    BaseUsages write() throws IOException;
  }
}
