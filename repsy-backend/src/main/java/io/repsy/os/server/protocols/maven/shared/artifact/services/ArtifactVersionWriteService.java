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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionDeveloper;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.VersionLicense;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import io.repsy.protocols.maven.shared.utils.ArtifactUtils;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.apache.maven.model.Model;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shared version-dependent write operations (developer/license rows, latest/release update) used by
 * both {@link ArtifactServiceImpl} (outer-transaction "already exists" update path) and {@link
 * ArtifactUpsertHelper} (REQUIRES_NEW "newly inserted" path).
 *
 * <p>Depends only on repositories, not on either of those two classes, so both can depend on this
 * one without forming a circular bean dependency. Its write methods use plain
 * {@code @Transactional} (default REQUIRED propagation) so they join whichever transaction the
 * caller already has active, rather than starting a new one.
 */
@Component
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
class ArtifactVersionWriteService {

  private final VersionDeveloperRepository versionDeveloperRepository;
  private final VersionLicenseRepository versionLicenseRepository;
  private final ArtifactVersionRepository artifactVersionRepository;
  private final ArtifactRepository artifactRepository;

  @Transactional
  void createVersionDevelopers(
      final @Nullable Model pomModel, final ArtifactVersion artifactVersion) {

    if (pomModel == null
        || pomModel.getDevelopers() == null
        || pomModel.getDevelopers().isEmpty()) {
      return;
    }

    for (final var developer : pomModel.getDevelopers()) {
      final var versionDeveloper = new VersionDeveloper();

      versionDeveloper.setArtifactVersion(artifactVersion);
      versionDeveloper.setName(developer.getName());
      versionDeveloper.setEmail(developer.getEmail());

      this.versionDeveloperRepository.save(versionDeveloper);
    }
  }

  @Transactional
  void createVersionLicenses(
      final @Nullable Model pomModel, final ArtifactVersion artifactVersion) {

    if (pomModel == null || pomModel.getLicenses() == null || pomModel.getLicenses().isEmpty()) {
      return;
    }

    for (final var license : pomModel.getLicenses()) {
      final var versionLicense = new VersionLicense();

      versionLicense.setArtifactVersion(artifactVersion);
      versionLicense.setName(license.getName());
      versionLicense.setUrl(license.getUrl());

      this.versionLicenseRepository.save(versionLicense);
    }
  }

  @Transactional
  void updateReleaseAndLatestVersion(final Artifact artifact) {

    final var versions = this.artifactVersionRepository.findByArtifactId(artifact.getId());
    final var versionNames = new ArrayList<String>();

    for (final var artifactVersion : versions) {
      versionNames.add(artifactVersion.getVersionName());
    }

    versionNames.sort(new VersionComparator());

    final var latest = versionNames.getLast();
    String release = null;

    for (var i = versionNames.size() - 1; i >= 0; i--) {
      if (!ArtifactUtils.isSnapshot(versionNames.get(i))) {
        release = versionNames.get(i);
        break;
      }
    }

    artifact.setLatest(latest);
    artifact.setRelease(release);

    this.artifactRepository.save(artifact);
  }
}
