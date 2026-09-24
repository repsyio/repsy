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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.events.ArtifactVersionDeletedEvent;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService.ManifestFileRef;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManifestDeletionComponent")
class ManifestDeletionComponentTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final UUID IMAGE_ID = UUID.randomUUID();
  private static final String IMAGE = "app";
  private static final String SHA256 = "sha256:" + "ab".repeat(32);
  private static final String SHA512 = "sha512:" + "cd".repeat(64);

  @Mock private ImageTxService imageService;
  @Mock private ManifestRepository manifestRepository;
  @Mock private TagRepository tagRepository;
  @Mock private ManifestFileService manifestFileService;
  @Mock private DockerStorageService dockerStorageService;
  @Mock private TagDeletionComponent tagDeletionComponent;
  @Mock private ApplicationEventPublisher eventPublisher;

  private ManifestDeletionComponent component;
  private RepoInfo repoInfo;
  private Manifest manifest;

  @BeforeEach
  void setUp() {
    this.component =
        new ManifestDeletionComponent(
            this.imageService,
            this.manifestRepository,
            this.tagRepository,
            this.manifestFileService,
            this.dockerStorageService,
            this.tagDeletionComponent,
            this.eventPublisher);
    this.repoInfo = RepoInfo.builder().storageKey(REPO_ID).name("docker").build();
    this.repoInfo.setType(RepoType.DOCKER);
    this.manifest = new Manifest();
    this.manifest.setId(UUID.randomUUID());
    this.manifest.setDigest(SHA256);
  }

  private static Stream<String> digestReferences() {
    return Stream.of(SHA256, SHA512, "sha256:" + "AB".repeat(32));
  }

  private void imageExists() {
    when(this.imageService.findImageInfoByRepoIdAndName(REPO_ID, IMAGE))
        .thenReturn(ImageInfo.builder().id(IMAGE_ID).name(IMAGE).build());
  }

  private static Tag tag(final String name) {
    final var tag = new Tag();
    tag.setName(name);
    return tag;
  }

  @Test
  @DisplayName("a tag reference deletes the tag exactly as the panel does, and frees nothing")
  void tagReferenceDeletesTheTagOnly() {
    final var freed = this.component.delete(this.repoInfo, IMAGE, "latest");

    assertThat(freed).isZero();
    verify(this.tagDeletionComponent).deleteTag(this.repoInfo, IMAGE, "latest");
    verifyNoInteractions(this.manifestRepository, this.dockerStorageService, this.eventPublisher);
  }

  @ParameterizedTest
  @MethodSource("digestReferences")
  @DisplayName("a digest reference of either algorithm, in any case, finds the manifest by it")
  void digestReferenceLooksUpEitherAlgorithm(final String reference) {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(eq(IMAGE_ID), any()))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(List.of());
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());

    this.component.delete(this.repoInfo, IMAGE, reference);

    verify(this.manifestRepository)
        .findByImageIdAndAnyDigest(IMAGE_ID, reference.toLowerCase(Locale.ROOT));
    verifyNoInteractions(this.tagDeletionComponent);
  }

  @Test
  @DisplayName("an unknown digest is manifestNotFound and nothing is deleted")
  void unknownDigestIsNotFound() {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.component.delete(this.repoInfo, IMAGE, SHA256))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("manifestNotFound");
    verify(this.manifestRepository, never()).delete(any());
    verifyNoInteractions(this.dockerStorageService, this.eventPublisher);
  }

  @Test
  @DisplayName("an unknown image is the image lookup's own not-found error")
  void unknownImageIsNotFound() {
    when(this.imageService.findImageInfoByRepoIdAndName(REPO_ID, IMAGE))
        .thenThrow(new ItemNotFoundException("imageNotFound"));

    assertThatThrownBy(() -> this.component.delete(this.repoInfo, IMAGE, SHA256))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("imageNotFound");
  }

  @Test
  @DisplayName("deletes the tags, then the manifest, then counts the rows left, then the file")
  void deletesTagsThenManifestThenFile() {
    this.imageExists();
    final var tags = List.of(tag("latest"), tag("v1"));
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(tags);
    when(this.manifestFileService.findUnreferencedFileNames(
            REPO_ID, IMAGE_ID, IMAGE, List.of(new ManifestFileRef(SHA256, null))))
        .thenReturn(Set.of(SHA256));
    when(this.dockerStorageService.deleteManifests(this.repoInfo, Set.of(SHA256))).thenReturn(321L);

    final var freed = this.component.delete(this.repoInfo, IMAGE, SHA256);

    assertThat(freed).isEqualTo(321L);
    final var order =
        inOrder(
            this.tagRepository,
            this.manifestRepository,
            this.manifestFileService,
            this.dockerStorageService);
    order.verify(this.tagRepository).deleteAll(tags);
    order.verify(this.manifestRepository).delete(this.manifest);
    order.verify(this.manifestRepository).flush();
    order.verify(this.manifestFileService).findUnreferencedFileNames(any(), any(), any(), any());
    order.verify(this.dockerStorageService).deleteManifests(eq(this.repoInfo), any());
  }

  @Test
  @DisplayName("publishes one version-deleted event per removed tag, and none without tags")
  void publishesAnEventPerTag() {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId()))
        .thenReturn(List.of(tag("latest"), tag("v1")));
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    final var captor = ArgumentCaptor.forClass(Object.class);
    verify(this.eventPublisher, times(2)).publishEvent(captor.capture());
    assertThat(captor.getAllValues())
        .allSatisfy(event -> assertThat(event).isInstanceOf(ArtifactVersionDeletedEvent.class))
        .extracting(event -> ((ArtifactVersionDeletedEvent) event).artifactVersion())
        .containsExactly("latest", "v1");
    assertThat(captor.getAllValues())
        .extracting(event -> (ArtifactVersionDeletedEvent) event)
        .allSatisfy(
            event -> {
              assertThat(event.repoId()).isEqualTo(REPO_ID);
              assertThat(event.repoType()).isEqualTo("DOCKER");
              assertThat(event.repoName()).isEqualTo("docker");
              assertThat(event.artifactName()).isEqualTo(IMAGE);
            });
  }

  @Test
  @DisplayName("a manifest without tags publishes nothing")
  void noTagsPublishesNothing() {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(List.of());
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    verifyNoInteractions(this.eventPublisher);
  }

  @Test
  @DisplayName("a legacy file name is handed on with the digest, so both files are considered")
  void passesTheLegacyStorageName() {
    this.imageExists();
    this.manifest.setStorageName("legacy-name");
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(List.of());
    when(this.manifestFileService.findUnreferencedFileNames(
            REPO_ID, IMAGE_ID, IMAGE, List.of(new ManifestFileRef(SHA256, "legacy-name"))))
        .thenReturn(Set.of("x"));

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    verify(this.dockerStorageService).deleteManifests(this.repoInfo, Set.of("x"));
  }

  @Test
  @DisplayName("locks the image row before it reads or deletes anything of the image")
  void locksTheImageFirst() {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(List.of());
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    final var order = inOrder(this.imageService, this.manifestRepository);
    order.verify(this.imageService).lockImage(IMAGE_ID);
    order.verify(this.manifestRepository).findByImageIdAndAnyDigest(IMAGE_ID, SHA256);
    order.verify(this.manifestRepository).delete(this.manifest);
    order.verify(this.imageService).deleteImageIfEmpty(REPO_ID, IMAGE_ID);
  }

  @Test
  @DisplayName("the image is refreshed while it still has manifests, and not once it is gone")
  void refreshesTheImageOnlyWhileItStays() {
    this.imageExists();
    when(this.manifestRepository.findByImageIdAndAnyDigest(IMAGE_ID, SHA256))
        .thenReturn(Optional.of(this.manifest));
    when(this.tagRepository.findAllByManifestId(this.manifest.getId())).thenReturn(List.of());
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());
    when(this.imageService.deleteImageIfEmpty(REPO_ID, IMAGE_ID)).thenReturn(false, true);

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    verify(this.imageService).refreshImageSize(REPO_ID, IMAGE_ID);

    this.component.delete(this.repoInfo, IMAGE, SHA256);

    verify(this.imageService, times(1)).refreshImageSize(REPO_ID, IMAGE_ID);
  }

  @Test
  @DisplayName("a tag reference never asks for the image to be deleted")
  void aTagReferenceNeverDeletesTheImage() {
    this.component.delete(this.repoInfo, IMAGE, "latest");

    verify(this.imageService, never()).deleteImageIfEmpty(any(), any());
  }
}
