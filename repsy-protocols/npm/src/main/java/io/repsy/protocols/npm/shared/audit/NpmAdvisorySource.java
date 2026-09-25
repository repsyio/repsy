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
package io.repsy.protocols.npm.shared.audit;

import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;

/** Knows the vulnerabilities of the package versions an audit asks about. */
@NullMarked
public interface NpmAdvisorySource<ID> {

  /**
   * Returns the advisories of the given package versions, in the scope of one repository.
   *
   * @param repoInfo The repository the audit was sent to; nothing of another repository is returned
   * @param versionsByName The requested versions by package name
   * @return The advisories of which at least one requested version is vulnerable
   */
  List<NpmAdvisory> findAdvisories(
      BaseRepoInfo<ID> repoInfo, Map<String, Set<String>> versionsByName);
}
