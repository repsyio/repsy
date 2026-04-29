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
package io.repsy.protocols.nuget.shared.storage.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface NuGetStorageService {

  BaseUsages writePackage(
      UUID repoId, String packageId, String version, byte[] nupkgBytes, byte[] nuspecBytes)
      throws IOException;

  Resource getNupkg(UUID repoId, String packageId, String version);

  Resource getNuspec(UUID repoId, String packageId, String version);

  void createRepo(UUID repoId);

  long deletePackageVersion(UUID repoId, String packageId, String version) throws IOException;

  long deletePackage(UUID repoId, String packageId) throws IOException;

  long deleteRepo(UUID repoId);
}
