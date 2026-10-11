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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionDeveloperRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.VersionLicenseRepository;
import java.util.UUID;
import org.apache.maven.model.Developer;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ArtifactVersionWriteService (RPS-2176)")
class ArtifactVersionWriteServiceTest {

  private final VersionDeveloperRepository developers = mock(VersionDeveloperRepository.class);
  private final VersionLicenseRepository licenses = mock(VersionLicenseRepository.class);
  private final ArtifactVersionRepository versions = mock(ArtifactVersionRepository.class);
  private final ArtifactVersionWriteService service =
      new ArtifactVersionWriteService(
          this.developers, this.licenses, this.versions, mock(ArtifactRepository.class));

  @Test
  @DisplayName(
      "replaceVersionDetails drops the old rows, writes the new ones and saves the version")
  void replaceVersionDetailsWritesEveryRowOfTheVersion() {
    final var version = new ArtifactVersion();
    version.setId(UUID.randomUUID());
    final var model = new Model();
    model.addDeveloper(new Developer());
    model.addLicense(new License());

    this.service.replaceVersionDetails(model, version);

    final var order = inOrder(this.developers, this.licenses, this.versions);

    order.verify(this.developers).deleteAllByArtifactVersionId(version.getId());
    order.verify(this.licenses).deleteAllByArtifactVersionId(version.getId());
    order.verify(this.developers).save(any());
    order.verify(this.licenses).save(any());
    order.verify(this.versions).save(version);
  }
}
