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
package io.repsy.protocols.ruby.shared.storage.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface RubyStorageService {

  BaseUsages writeGem(
      UUID repoId, String repoName, String gemName, String version, String platform, byte[] bytes);

  Resource getGem(UUID repoId, String repoName, String filename);

  long deleteGem(UUID repoId, String repoName, String gemName, String version, String platform);

  long deleteAllGems(UUID repoId, String repoName, String gemName);

  void createRepo(UUID repoId);

  long deleteRepo(UUID repoId);

  /** Storage-relative path of a gem version: {@code gems/{gemName}/{gemName}-{version}[-{platform}].gem}. */
  String getGemRelativePath(String gemName, String version, String platform);
}
