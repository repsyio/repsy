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
package io.repsy.os.server.protocols.maven.shared.artifact.services.components;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.maven.shared.artifact.dtos.DeletedItem;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * A delete of something that is not there is a 404 that touches neither storage nor rows, whatever
 * else the group holds. Before, an artifact name that does not exist in a group with exactly one
 * artifact reached {@code hasOnlyOneArtifact} as true and deleted the whole group (RPS-1573, the
 * shape RPS-1190 fixed for versions), and a group without artifacts answered 200 {@code GROUP}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Maven ArtifactDeletionComponent existence checks (RPS-1573)")
class ArtifactDeletionComponentTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final String GROUP = "com.acme";

  @Mock private MavenStorageService mavenStorageService;
  @Mock private ArtifactServiceImpl artifactService;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private ArtifactDeletionComponent component;

  private static RepoInfo repo() {
    return RepoInfo.builder()
        .id(REPO_ID)
        .storageKey(REPO_ID)
        .name("mvn")
        .type(RepoType.MAVEN)
        .build();
  }

  @Test
  @DisplayName("an artifact that does not exist is a 404 before hasOnlyOneArtifact is asked")
  void missingArtifactIsRefusedBeforeTheCascade() {
    doThrow(new ItemNotFoundException("artifactNotFound"))
        .when(this.artifactService)
        .requireArtifact(REPO_ID, GROUP, "ghost");

    assertThatThrownBy(() -> this.component.deleteArtifact(repo(), GROUP, "ghost"))
        .isInstanceOf(ItemNotFoundException.class);

    // The group holds exactly one artifact in the reported case: the answer must not depend on it,
    // and nothing may be moved or removed.
    verify(this.artifactService).requireArtifact(REPO_ID, GROUP, "ghost");
    verifyNoMoreInteractions(this.artifactService);
    verifyNoInteractions(this.mavenStorageService, this.eventPublisher);
  }

  @Test
  @DisplayName("a group without artifacts is a 404 that deletes nothing")
  void missingGroupIsRefused() {
    doThrow(new ItemNotFoundException("groupNotFound"))
        .when(this.artifactService)
        .requireGroup(REPO_ID, GROUP);

    assertThatThrownBy(() -> this.component.deleteGroup(repo(), GROUP))
        .isInstanceOf(ItemNotFoundException.class);

    verify(this.artifactService).requireGroup(REPO_ID, GROUP);
    verifyNoMoreInteractions(this.artifactService);
    verifyNoInteractions(this.mavenStorageService, this.eventPublisher);
  }

  @Test
  @DisplayName("the last real artifact of a group still takes the group with it (RPS-1348)")
  void theOnlyRealArtifactStillCascadesToTheGroup() {
    final var artifact = new Artifact();
    artifact.setArtifactName("lib");
    when(this.artifactService.hasOnlyOneArtifact(REPO_ID, GROUP)).thenReturn(true);
    when(this.artifactService.getArtifacts(REPO_ID, GROUP)).thenReturn(List.of(artifact));
    when(this.artifactService.getArtifactVersionNames(REPO_ID, GROUP, "lib"))
        .thenReturn(List.of("1.0"));
    when(this.mavenStorageService.deleteGroup(REPO_ID, GROUP, List.of("lib"))).thenReturn(10L);

    final var deleted = this.component.deleteArtifact(repo(), GROUP, "lib");

    assertThat(deleted.getFirst()).isEqualTo(DeletedItem.GROUP);
    verify(this.artifactService).requireArtifact(REPO_ID, GROUP, "lib");
    verify(this.artifactService).requireGroup(REPO_ID, GROUP);
    verify(this.artifactService).deleteGroup(REPO_ID, GROUP);
    verify(this.eventPublisher).publishEvent(any(Object.class));
  }

  @Test
  @DisplayName("an artifact of a group with siblings is deleted alone")
  void anArtifactWithSiblingsIsDeletedAlone() {
    when(this.artifactService.hasOnlyOneArtifact(REPO_ID, GROUP)).thenReturn(false);
    when(this.artifactService.getArtifactVersionNames(REPO_ID, GROUP, "lib"))
        .thenReturn(List.of("1.0", "2.0"));
    when(this.mavenStorageService.deleteArtifact(REPO_ID, GROUP, "lib")).thenReturn(5L);

    final var deleted = this.component.deleteArtifact(repo(), GROUP, "lib");

    assertThat(deleted.getFirst()).isEqualTo(DeletedItem.ARTIFACT);
    verify(this.artifactService).deleteArtifact(REPO_ID, GROUP, "lib");
  }
}
