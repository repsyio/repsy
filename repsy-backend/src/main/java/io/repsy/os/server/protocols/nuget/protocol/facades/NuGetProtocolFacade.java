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
package io.repsy.os.server.protocols.nuget.protocol.facades;

import static org.springframework.http.HttpStatus.CONFLICT;

import io.repsy.protocols.nuget.protocol.facades.AbstractNuGetProtocolFacade;
import io.repsy.protocols.nuget.shared.packages.services.NuGetPackageService;
import io.repsy.protocols.nuget.shared.storage.services.NuGetStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@NullMarked
@Component
public class NuGetProtocolFacade extends AbstractNuGetProtocolFacade<UUID> {

  public NuGetProtocolFacade(
      final NuGetStorageService nuGetStorageService,
      final NuGetPackageService<UUID> nuGetPackageService) {

    super(nuGetStorageService, nuGetPackageService);
  }

  @Override
  protected void doPublish(
      final BaseRepoInfo<UUID> repoInfo,
      final UUID pkgId,
      final String version,
      final String nuspecXml)
      throws IOException {

    try {
      super.doPublish(repoInfo, pkgId, version, nuspecXml);
    } catch (final DataIntegrityViolationException e) {
      throw new ResponseStatusException(CONFLICT, "Version %s already exists.".formatted(version));
    }
  }
}
