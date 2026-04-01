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
package io.repsy.protocols.cargo.protocol.facade;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.facade.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishDep;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractCargoProtocolFacade<ID> implements CargoProtocolFacade<ID> {

  private static final int TWO = 2;
  private static final int THREE = 3;
  private static final String USAGES = "usages";
  private static final String SHA256 = "SHA-256";

  private static final Pattern CRATE_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_-]*$");
  private static final int MAX_NAME_LENGTH = 64;
  private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+.*$");
  private static final int MAX_KEYWORDS = 5;
  private static final int MAX_KEYWORD_LENGTH = 20;

  private final CargoStorageService cargoStorageService;
  private final CargoCrateService<ID> cargoCrateService;
  private final ObjectMapper objectMapper;

  @Override
  public List<CrateIndexEntry> getIndexEntries(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var crateName = extractLastSegment(context);

    return this.cargoCrateService.getIndexEntries(repoInfo, crateName);
  }

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var repoId = repoInfo.getStorageKey();
    final var segments = splitPath(context);
    final var crateName = normalizeCrateName(segments[segments.length - THREE]);
    final var versionName = segments[segments.length - TWO];

    final var resource =
        this.cargoStorageService.getCrate(repoId, repoInfo.getName(), crateName, versionName);

    this.cargoCrateService.incrementDownloadCount(repoInfo, crateName, versionName);

    return resource;
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
      throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var repoId = repoInfo.getStorageKey();

    final var jsonLength = readU32LittleEndian(inputStream);
    final var jsonBytes = inputStream.readNBytes((int) jsonLength);
    final var request = this.objectMapper.readValue(jsonBytes, CratePublishRequest.class);

    validatePublishRequest(request);

    final var crateLength = readU32LittleEndian(inputStream);
    final var crateBytes = inputStream.readNBytes((int) crateLength);
    final var checksum = computeSha256(crateBytes);

    final var crateName = request.name().toLowerCase().replace('-', '_');

    final var requestWithChecksum =
        new CratePublishRequest(
            request.name(),
            request.vers(),
            request.deps(),
            request.features(),
            request.authors(),
            request.description(),
            request.documentation(),
            request.homepage(),
            request.readme(),
            request.readmeFile(),
            request.keywords(),
            request.categories(),
            request.license(),
            request.licenseFile(),
            request.repository(),
            request.links(),
            request.rustVersion(),
            checksum,
            request.features2());

    this.cargoCrateService.publish(repoInfo, requestWithChecksum);

    final var indexEntry = buildIndexEntry(requestWithChecksum);
    final var indexJsonLine = this.objectMapper.writeValueAsString(indexEntry);

    final var usages =
        this.cargoStorageService.writeCrateAndIndex(
            repoId, repoInfo.getName(), crateName, request.vers(), crateBytes, indexJsonLine);

    context.addProperty(USAGES, usages);
  }

  @Override
  public void yank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var segments = splitPath(context);
    final var crateName = normalizeCrateName(segments[segments.length - THREE]);
    final var vers = segments[segments.length - TWO];

    this.cargoCrateService.yank(repoInfo, crateName, vers);
  }

  @Override
  public void unyank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var segments = splitPath(context);
    final var crateName = normalizeCrateName(segments[segments.length - THREE]);
    final var vers = segments[segments.length - TWO];

    this.cargoCrateService.unyank(repoInfo, crateName, vers);
  }

  //  @Override
  //  public List<CargoOwnerItem> listOwners(
  //      final ProtocolContext context, final @Nullable String authHeader) {
  //    return List.of();
  //  }
  //
  //  @Override
  //  public void addOwners(final ProtocolContext context, final List<String> logins) {}
  //
  //  @Override
  //  public void removeOwners(final ProtocolContext context, final List<String> logins) {}

  @Override
  public Page<CrateListItem> search(
      final ProtocolContext context, final String query, final Pageable pageable) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.cargoCrateService.search(repoInfo, query, pageable);
  }

  @Override
  public CrateInfo getCrate(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var segments = splitPath(context);
    final var name = segments[segments.length - 1];

    return this.cargoCrateService.getCrate(repoInfo, name);
  }

  @Override
  public CrateVersionInfo getCrateVersion(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var segments = splitPath(context);
    final var name = segments[segments.length - 2];
    final var vers = segments[segments.length - 1];

    return this.cargoCrateService.getCrateVersion(repoInfo, name, vers);
  }

  private static CrateIndexEntry buildIndexEntry(final CratePublishRequest request) {

    final var deps =
        request.deps() == null
            ? List.<CrateIndexDep>of()
            : request.deps().stream().map(AbstractCargoProtocolFacade::toIndexDep).toList();

    final var features =
        request.features() != null ? request.features() : Map.<String, List<String>>of();

    final var v = request.features2() != null ? 2 : 1;

    return new CrateIndexEntry(
        request.name(),
        request.vers(),
        deps,
        request.cksum(),
        features,
        false,
        request.links(),
        v,
        request.features2(),
        request.rustVersion());
  }

  private static CrateIndexDep toIndexDep(final CratePublishDep dep) {

    final String packageName;
    final String name;

    if (dep.explicitNameInToml() != null) {
      name = dep.explicitNameInToml();
      packageName = dep.name();
    } else {
      name = dep.name();
      packageName = null;
    }

    return new CrateIndexDep(
        name,
        dep.versionReq(),
        dep.features(),
        dep.optional(),
        dep.defaultFeatures(),
        dep.target(),
        dep.kind(),
        dep.registry(),
        packageName);
  }

  private static void validatePublishRequest(final CratePublishRequest request) {
    validateCrateName(request.name());
    validateVersion(request.vers());
    validateKeywords(request.keywords());
  }

  private static void validateCrateName(final @Nullable String name) {

    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("crate name cannot be empty");
    }

    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "crate name `%s` must be at most %d characters".formatted(name, MAX_NAME_LENGTH));
    }

    if (!CRATE_NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "crate name `%s` must start with an alphanumeric character and contain only alphanumerics, `-`, or `_`"
              .formatted(name));
    }
  }

  private static void validateVersion(final @Nullable String vers) {

    if (vers == null || vers.isBlank()) {
      throw new IllegalArgumentException("version cannot be empty");
    }

    if (!SEMVER_PATTERN.matcher(vers).matches()) {
      throw new IllegalArgumentException(
          "version `%s` is not a valid semver format (expected MAJOR.MINOR.PATCH)".formatted(vers));
    }
  }

  private static void validateKeywords(final @Nullable List<String> keywords) {

    if (keywords == null) {
      return;
    }

    if (keywords.size() > MAX_KEYWORDS) {
      throw new IllegalArgumentException(
          "a crate may have at most %d keywords, got %d".formatted(MAX_KEYWORDS, keywords.size()));
    }

    for (final var kw : keywords) {
      validateKeyword(kw);
    }
  }

  private static void validateKeyword(final String kw) {

    if (kw.length() > MAX_KEYWORD_LENGTH) {
      throw new IllegalArgumentException(
          "keyword `%s` must be at most %d characters".formatted(kw, MAX_KEYWORD_LENGTH));
    }
  }

  private static long readU32LittleEndian(final InputStream inputStream) throws IOException {
    final var bytes = inputStream.readNBytes(4);
    return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt());
  }

  private static String computeSha256(final byte[] bytes) {
    try {
      final var digest = MessageDigest.getInstance(SHA256);
      final var hash = digest.digest(bytes);
      return HexFormat.of().formatHex(hash);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(SHA256 + " algorithm not available", e);
    }
  }

  private static String[] splitPath(final ProtocolContext context) {
    return ProtocolContextUtils.getRelativePath(context).getPath().split("/");
  }

  private static String extractLastSegment(final ProtocolContext context) {
    final var segments = splitPath(context);
    return segments[segments.length - 1];
  }

  private static String normalizeCrateName(final String name) {
    return name.toLowerCase().replace('-', '_');
  }
}
