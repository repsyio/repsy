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
package io.repsy.protocols.nuget.shared.packages.services;

import io.repsy.protocols.nuget.shared.dtos.NuGetSearchResponse;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@NullMarked
public interface NuGetPackageService<ID> {

  void publish(BaseRepoInfo<ID> repoInfo, String packageId, String version, String nuspecXml)
      throws IOException;

  List<String> getVersions(BaseRepoInfo<ID> repoInfo, String packageId);

  Page<String> search(
      BaseRepoInfo<ID> repoInfo, String query, int skip, int take, boolean prerelease);

  List<String> autocomplete(
      BaseRepoInfo<ID> repoInfo, String query, int skip, int take, boolean prerelease);

  void incrementDownloadCount(BaseRepoInfo<ID> repoInfo, String packageId, String version);
}
