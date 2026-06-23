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

import io.repsy.protocols.ruby.shared.gem.dtos.GemCompactEntry;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.List;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface RubyGemProtocolService<ID> {

  List<String> getGemNames(BaseRepoInfo<ID> repoInfo);

  List<GemCompactEntry> getAllCompactEntries(BaseRepoInfo<ID> repoInfo);

  List<GemCompactEntry> getCompactEntriesByGemName(BaseRepoInfo<ID> repoInfo, String gemName);

  void publishGem(BaseRepoInfo<ID> repoInfo, GemMetadata metadata, String checksum);

  void yankGem(BaseRepoInfo<ID> repoInfo, String gemName, String version, String platform);
}
