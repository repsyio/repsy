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
package io.repsy.os.server.protocols.maven.shared.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactRepository;
import io.repsy.os.server.protocols.maven.shared.artifact.repositories.ArtifactVersionRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

/**
 * RPS-2077: an artifact with no {@code latest} and a version with no {@code last_updated_at} (both
 * nullable columns, which the metadata of a deployment that carries no version list leaves null).
 * The Maven packages are {@code @NullMarked}, and Spring Data guards the getters of an interface
 * projection under the marking: a getter that answers null without {@code @Nullable} throws {@code
 * "Return value is null but must not be null"}, so the list answered 500 where it answered the null
 * before the marking. This is the flip-and-fail of the {@code @Nullable} on {@code
 * ArtifactListItem#getLatest} and {@code ArtifactVersionListItem#getLastUpdatedAt}.
 */
@DisplayName("Maven projections of nullable columns (RPS-2077)")
class MavenNullableProjectionIT extends AbstractIT {

  @Autowired private ArtifactRepository artifactRepository;
  @Autowired private ArtifactVersionRepository artifactVersionRepository;

  private Artifact seedArtifactWithoutLatest(final Repo repo) {
    final var artifact = new Artifact();

    artifact.setRepo(this.entityManager.getReference(Repo.class, repo.getId()));
    artifact.setGroupName("io.repsy.null");
    artifact.setArtifactName("no-latest");
    this.entityManager.persist(artifact);

    final var version = new ArtifactVersion();

    version.setArtifact(artifact);
    version.setType(ArtifactVersionType.RELEASE);
    version.setVersionName("1.0.0");
    this.entityManager.persist(version);
    this.entityManager.flush();
    this.entityManager.clear();

    return artifact;
  }

  @Test
  @DisplayName("the artifact list answers a null latest")
  void artifactListAnswersNullLatest() {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("maven"));
    this.seedArtifactWithoutLatest(repo);

    final var page =
        this.artifactRepository.findAllByRepoIdAndContainsGroupName(
            repo.getId(), "%io.repsy.null%", Pageable.unpaged());

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getArtifactName()).isEqualTo("no-latest");
    assertThat(page.getContent().get(0).getLatest()).isNull();
  }

  @Test
  @DisplayName("the version list answers a null last update")
  void versionListAnswersNullLastUpdatedAt() {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("maven"));
    this.seedArtifactWithoutLatest(repo);

    final var page =
        this.artifactVersionRepository.findAllByRepoIdAndGroupNameAndArtifactName(
            repo.getId(), "io.repsy.null", "no-latest", Pageable.unpaged());

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).getVersionName()).isEqualTo("1.0.0");
    assertThat(page.getContent().get(0).getLastUpdatedAt()).isNull();
  }
}
