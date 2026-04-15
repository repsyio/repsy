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
package io.repsy.protocols.cargo.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.facades.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.protocol.utils.CargoDigestCalculator;
import io.repsy.protocols.cargo.protocol.utils.CrateUtils;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@RequiredArgsConstructor
public abstract class AbstractCargoProtocolFacade<ID> implements CargoProtocolFacade {

  private static final String USAGES = "usages";

  private final CargoStorageService cargoStorageService;
  private final CargoCrateService<ID> cargoCrateService;
  private final ObjectMapper objectMapper;

  @Override
  public List<CrateIndexEntry> getIndexEntries(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var crateName = CrateUtils.extractLastSegment(context);

    return this.cargoCrateService.getIndexEntries(repoInfo, crateName);
  }

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var crateNameAndVersionPair = CrateUtils.extractCrateNameAndVersion(context);
    final var crateName = crateNameAndVersionPair.getFirst();
    final var versionName = crateNameAndVersionPair.getSecond();

    final var resource =
        this.cargoStorageService.getCrate(
            repoInfo.getStorageKey(), repoInfo.getName(), crateName, versionName);

    this.cargoCrateService.incrementDownloadCount(repoInfo, crateName, versionName);

    return resource;
  }

  // [ 4 byte JSON len ] [ JSON bytes ] [ 4 byte .crate len ] [ .crate bytes ]
  // https://doc.rust-lang.org/cargo/reference/registry-web-api.html#publish
  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var request = CrateUtils.getPublishRequest(inputStream, this.objectMapper);

    CrateUtils.validatePublishRequest(request);

    final var crateBytes = CrateUtils.getCrateBytes(inputStream);
    final var checksum = CargoDigestCalculator.computeDigest(crateBytes);
    final var hasLib = CrateUtils.isLib(crateBytes);
    final var crateName = CrateUtils.normalizeCrateName(request.name());

    final var requestWithChecksum =
        CrateUtils.createCratePublishRequestWithChecksum(request, checksum, hasLib);

    final var indexJsonLine = CrateUtils.getIndexJsonLine(requestWithChecksum, this.objectMapper);

    final var usages =
        this.cargoStorageService.writeCrateAndIndex(
            repoInfo.getStorageKey(),
            repoInfo.getName(),
            crateName,
            request.vers(),
            crateBytes,
            indexJsonLine);

    this.cargoCrateService.publish(repoInfo, requestWithChecksum);

    context.addProperty(USAGES, usages);
  }

  @Override
  public void yank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var nameVersionPair = CrateUtils.extractCrateNameAndVersion(context);

    this.cargoCrateService.yank(repoInfo, nameVersionPair.getFirst(), nameVersionPair.getSecond());
  }

  @Override
  public void unyank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    final var nameVersionPair = CrateUtils.extractCrateNameAndVersion(context);

    this.cargoCrateService.unyank(
        repoInfo, nameVersionPair.getFirst(), nameVersionPair.getSecond());
  }

  @Override
  public Page<CrateListItem> search(
      final ProtocolContext context, final String query, final Pageable pageable) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.cargoCrateService.search(repoInfo, query, pageable);
  }
}
