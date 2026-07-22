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
package io.repsy.protocols.maven.protocol.facades;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.protocol.facades.contracts.MavenProtocolFacade;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.services.contracts.ArtifactService;
import io.repsy.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.maven.index.artifact.Gav;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@RequiredArgsConstructor
@NullMarked
public abstract class AbstractMavenProtocolFacade<ID> implements MavenProtocolFacade<ID> {

  private static final String USAGES = "usages";
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";

  /**
   * Only these packaging types are archives a vulnerability scanner can actually analyze (compiled
   * classes / embedded dependency metadata) — {@code pom} (plain XML) and {@code module} (Gradle
   * Module Metadata, JSON) carry no scannable content. Checksum sidecar files (handled separately
   * via {@link ArtifactUtils#isChecksumFile}) resolve to the extension of the file they checksum
   * (e.g. {@code foo.jar.sha1} → {@code jar}), so extension alone cannot tell them apart.
   */
  private static final Set<String> SCANNABLE_EXTENSIONS = Set.of("jar", "war", "ear");

  private final MavenStorageService<ID> mavenStorageService;
  private final ArtifactService<ID> artifactService;

  @Override
  public Resource download(final ProtocolContext context) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    return this.mavenStorageService.getResource(repoInfo.getName(), storagePath);
  }

  @Override
  public void upload(
      final ProtocolContext context, final InputStream inputStream, final long contentLength)
      throws IOException, XmlPullParserException {

    final var repoInfo = ProtocolContextUtils.<ID>getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);
    final var storagePath = StoragePath.of(repoInfo.getStorageKey(), relativePath.getPath());

    byte[] content = null;

    final var fileName = storagePath.getRelativePath().getFileName();
    final MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair;

    if (ArtifactUtils.isFileSuitableForGavExtraction(fileName)) {
      artifactPair = this.artifactService.getDeployAndVersionType(repoInfo, storagePath);
    } else {
      content = inputStream.readAllBytes();
      artifactPair =
          this.artifactService.getDeployAndVersionTypesByMetadataTypeFiles(
              repoInfo, content, fileName);
    }

    if (artifactPair == null) {
      return;
    }

    this.artifactService.checkDeploymentRules(repoInfo, artifactPair, storagePath);

    final var afterUploadUsage =
        this.mavenStorageService.writeInputStreamToPath(
            storagePath,
            content == null ? inputStream : new ByteArrayInputStream(content),
            repoInfo.getName());

    final var resource = this.mavenStorageService.getResource(repoInfo.getName(), storagePath);

    this.artifactService.createOrUpdateArtifact(repoInfo, storagePath, resource);

    final var gav = ArtifactUtils.getGavByFile(storagePath);

    if (gav != null && isScannableArtifact(gav, fileName)) {
      context.addProperty(ARTIFACT_NAME, gav.getGroupId() + ":" + gav.getArtifactId());
      context.addProperty(ARTIFACT_VERSION, resolveLogicalVersion(gav));
    }

    context.addProperty(USAGES, afterUploadUsage);
  }

  /**
   * Matches {@code ArtifactServiceImpl#createArtifactVersionByGav}'s own {@code versionName}
   * formula exactly — {@code gav.getVersion()} alone resolves to the physical, timestamped SNAPSHOT
   * build string (e.g. {@code 1.0-20260720.130651-1}), which is never the identity persisted to
   * {@code maven_artifact_version} and never a coordinate any other part of the app (UI, manual
   * re-scan, storage resolver) looks up by.
   */
  private static String resolveLogicalVersion(final Gav gav) {
    return gav.isSnapshot() ? gav.getBaseVersion() : gav.getVersion();
  }

  /**
   * Excludes {@code .pom}/{@code .module} metadata files, checksum sidecar files, and attached
   * {@code sources}/{@code javadoc} classifier jars from triggering a vulnerability scan — a single
   * {@code mvn deploy} pushes the pom, the primary jar, and a checksum sidecar for each (up to 6-10
   * requests), and only the primary jar/war/ear actually has scannable content. {@code
   * gav.getExtension()} can itself be {@code null} — {@link ArtifactUtils#getGavByFile} routes
   * artifact-level {@code maven-metadata.xml} PUTs (no version segment, e.g. the "latest version"
   * pointer update after every deploy) through a path that never sets it — and {@code
   * Set.of(...).contains(null)} throws rather than returning {@code false}, so that has to be
   * checked explicitly first.
   */
  private static boolean isScannableArtifact(final Gav gav, final String fileName) {
    final var extension = gav.getExtension();

    return extension != null
        && !ArtifactUtils.isChecksumFile(fileName)
        && gav.getClassifier() == null
        && SCANNABLE_EXTENSIONS.contains(extension);
  }
}
