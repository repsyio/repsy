package io.repsy.protocols.cargo.protocol.facade;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.cargo.protocol.facade.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateIndexEntry;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateInfo;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.protocols.cargo.shared.crate.dtos.CratePublishRequest;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateVersionInfo;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractCargoProtocolFacade<ID> implements CargoProtocolFacade<ID> {

  private static final String USAGES = "usages";
  private static final String SHA256 = "SHA-256";

  private final CargoStorageService<ID> cargoStorageService;
  private final CargoCrateService<ID> cargoCrateService;
  private final ObjectMapper objectMapper;

  @Override
  public List<CrateIndexEntry> getIndexEntries(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var crateName = extractCrateNameFromIndexPath(context);
    final var repoId = (UUID) repoInfo.getStorageKey();

    final var indexResource = this.cargoStorageService.getIndex(
      repoId, repoInfo.getName(), crateName);

    return parseIndexResource(indexResource);
  }

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var repoId = (UUID) repoInfo.getStorageKey();
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    final var crateName = segments[segments.length - 3];
    final var versionName = segments[segments.length - 2];

    return this.cargoStorageService.getCrate(repoId, repoInfo.getName(), crateName, versionName);
  }

  @Override
  public void publish(final ProtocolContext context, final InputStream inputStream)
    throws IOException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var repoId = (UUID) repoInfo.getStorageKey();

    final var jsonLength = readU32LittleEndian(inputStream);
    final var jsonBytes = inputStream.readNBytes((int) jsonLength);
    final var request = this.objectMapper.readValue(jsonBytes, CratePublishRequest.class);

    final var crateLength = readU32LittleEndian(inputStream);
    final var crateBytes = inputStream.readNBytes((int) crateLength);
    final var cksum = computeSha256(crateBytes);

    final var crateName = request.name().toLowerCase().replace('-', '_');

    final var requestWithCksum = new CratePublishRequest(
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
      cksum,
      request.features2());

    this.cargoCrateService.publish(repoInfo, requestWithCksum);

    final var indexEntry = this.cargoCrateService.getIndexEntry(repoInfo, crateName, request.vers());
    final var indexJsonLine = this.objectMapper.writeValueAsString(indexEntry);

    final var usages = this.cargoStorageService.writeCrateAndIndex(
      repoId,
      repoInfo.getName(),
      crateName,
      request.vers(),
      crateBytes,
      indexJsonLine);

    context.addProperty(USAGES, usages);
  }

  @Override
  public void yank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var name = extractCrateNameFromYankPath(context);
    final var vers = extractVersionFromYankPath(context);

    this.cargoCrateService.yank(repoInfo, name, vers);
  }

  @Override
  public void unyank(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var name = extractCrateNameFromYankPath(context);
    final var vers = extractVersionFromYankPath(context);

    this.cargoCrateService.unyank(repoInfo, name, vers);
  }

  @Override
  public Page<CrateListItem> search(
    final ProtocolContext context, final String query, final Pageable pageable) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);

    return this.cargoCrateService.search(repoInfo, query, pageable);
  }

  @Override
  public CrateInfo getCrate(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var name = extractCrateNameFromApiPath(context);

    return this.cargoCrateService.getCrate(repoInfo, name);
  }

  @Override
  public CrateVersionInfo getCrateVersion(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var name = extractCrateNameFromApiPath(context);
    final var vers = extractVersionFromApiPath(context);

    return this.cargoCrateService.getCrateVersion(repoInfo, name, vers);
  }

  private List<CrateIndexEntry> parseIndexResource(final Resource resource) {

    final var entries = new ArrayList<CrateIndexEntry>();

    try (final var reader = new BufferedReader(
      new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {

      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          entries.add(this.objectMapper.readValue(line, CrateIndexEntry.class));
        }
      }

    } catch (final IOException e) {
      return List.of();
    }

    return entries;
  }

  private static long readU32LittleEndian(final InputStream inputStream) throws IOException {
    final var bytes = inputStream.readNBytes(4);
    return Integer.toUnsignedLong(
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt());
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

  private static String extractCrateNameFromIndexPath(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    return segments[segments.length - 1];
  }

  private static String extractCrateNameFromYankPath(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    return segments[segments.length - 3];
  }

  private static String extractVersionFromYankPath(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    return segments[segments.length - 2];
  }

  private static String extractCrateNameFromApiPath(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    return segments[segments.length - 1];
  }

  private static String extractVersionFromApiPath(final ProtocolContext context) {
    final var path = ProtocolContextUtils.getRelativePath(context).getPath();
    final var segments = path.split("/");
    return segments[segments.length - 1];
  }
}
