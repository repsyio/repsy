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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService.ManifestFileRef;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UntaggedManifestCleanupService")
class UntaggedManifestCleanupServiceTest {

  private static final UUID REPO_ID = UUID.randomUUID();
  private static final RepoInfo REPO =
      RepoInfo.builder().storageKey(REPO_ID).name("docker").build();

  private final ImageRepository imageRepository = mock(ImageRepository.class);
  private final ImageTxService imageService = mock(ImageTxService.class);
  private final ManifestRepository manifestRepository = mock(ManifestRepository.class);
  private final ManifestFileService manifestFileService = mock(ManifestFileService.class);
  private final UntaggedManifestFinder finder = mock(UntaggedManifestFinder.class);
  private final DockerStorageService storage = mock(DockerStorageService.class);
  private final UntaggedManifestCleanupService service =
      new UntaggedManifestCleanupService(
          this.imageRepository,
          this.imageService,
          this.manifestRepository,
          this.manifestFileService,
          this.finder,
          this.storage);

  private static Image image(final String name) {
    final var image = new Image();
    image.setId(UUID.randomUUID());
    image.setName(name);

    return image;
  }

  private static Manifest manifest(final String digest) {
    final var manifest = new Manifest();
    manifest.setId(UUID.randomUUID());
    manifest.setDigest(digest);

    return manifest;
  }

  @Test
  @DisplayName("deletes the rows, then the files no remaining row needs, and reports both")
  void deletesRowsThenFiles() {
    final var app = image("app");
    final var old = manifest("sha256:old");
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.of(app));
    when(this.finder.findUntagged(app.getId())).thenReturn(List.of(old));
    final var refs = List.of(new ManifestFileRef("sha256:old", null));
    when(this.manifestFileService.findUnreferencedFileNames(REPO_ID, app.getId(), "app", refs))
        .thenReturn(Set.of("sha256:old"));
    when(this.storage.deleteManifests(REPO, Set.of("sha256:old"))).thenReturn(321L);

    final var result = this.service.deleteUntagged(REPO, null);

    assertThat(result).isEqualTo(new UntaggedManifestCleanupService.Result(1, 321L));
    final var order = inOrder(this.manifestRepository, this.manifestFileService, this.storage);
    order.verify(this.manifestRepository).delete(old);
    order.verify(this.manifestRepository).flush();
    order
        .verify(this.manifestFileService)
        .findUnreferencedFileNames(REPO_ID, app.getId(), "app", refs);
    order.verify(this.storage).deleteManifests(REPO, Set.of("sha256:old"));
  }

  @Test
  @DisplayName("adds up the images of the repo and leaves an image without untagged manifests")
  void sumsUpTheImagesOfTheRepo() {
    final var first = image("first");
    final var second = image("second");
    final var empty = image("empty");
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.of(first, empty, second));
    when(this.finder.findUntagged(first.getId())).thenReturn(List.of(manifest("sha256:a")));
    when(this.finder.findUntagged(empty.getId())).thenReturn(List.of());
    when(this.finder.findUntagged(second.getId()))
        .thenReturn(List.of(manifest("sha256:b"), manifest("sha256:c")));
    when(this.storage.deleteManifests(any(), anyCollection())).thenReturn(10L, 20L);

    final var result = this.service.deleteUntagged(REPO, null);

    assertThat(result).isEqualTo(new UntaggedManifestCleanupService.Result(3, 30L));
    verify(this.manifestRepository, times(3)).delete(any());
  }

  @Test
  @DisplayName("touches neither rows nor storage for a repo without images")
  void aRepoWithoutImagesHasNothingToDelete() {
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.of());

    assertThat(this.service.deleteUntagged(REPO, null))
        .isEqualTo(new UntaggedManifestCleanupService.Result(0, 0L));
    verifyNoInteractions(this.manifestRepository, this.storage);
  }

  @Test
  @DisplayName("only looks at the named image")
  void onlyTheNamedImage() {
    final var app = image("app");
    when(this.imageRepository.findByRepoIdAndName(REPO_ID, "app")).thenReturn(Optional.of(app));
    when(this.finder.findUntagged(app.getId())).thenReturn(List.of());

    assertThat(this.service.deleteUntagged(REPO, "app"))
        .isEqualTo(new UntaggedManifestCleanupService.Result(0, 0L));
    verify(this.imageRepository, never()).findAllByRepoId(any());
  }

  @Test
  @DisplayName("an unknown image is a 404 and nothing is deleted")
  void anUnknownImageIsNotFound() {
    when(this.imageRepository.findByRepoIdAndName(REPO_ID, "ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.service.deleteUntagged(REPO, "ghost"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessageContaining("imageNotFound");
    verifyNoInteractions(this.manifestRepository, this.storage);
  }

  @Test
  @DisplayName("locks each image, then deletes it if the rows it deleted were its last manifests")
  void locksThenDeletesTheImageItEmptied() {
    final var app = image("app");
    final var old = manifest("sha256:old");
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.of(app));
    when(this.finder.findUntagged(app.getId())).thenReturn(List.of(old));
    when(this.manifestFileService.findUnreferencedFileNames(any(), any(), any(), anyCollection()))
        .thenReturn(Set.of());

    this.service.deleteUntagged(REPO, null);

    final var order = inOrder(this.imageService, this.finder, this.manifestRepository);
    order.verify(this.imageService).lockImage(app.getId());
    order.verify(this.finder).findUntagged(app.getId());
    order.verify(this.manifestRepository).flush();
    order.verify(this.imageService).deleteImageIfEmpty(REPO_ID, app.getId());
  }

  @Test
  @DisplayName("an image with no untagged manifest is still checked, so one with no manifest goes")
  void anImageWithoutUntaggedManifestsIsStillChecked() {
    final var empty = image("empty");
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.of(empty));
    when(this.finder.findUntagged(empty.getId())).thenReturn(List.of());

    this.service.deleteUntagged(REPO, null);

    verify(this.imageService).deleteImageIfEmpty(REPO_ID, empty.getId());
    verifyNoInteractions(this.storage);
  }

  @Test
  @DisplayName("locks the images of a repo in id order, so two cleanups cannot deadlock")
  void locksTheImagesInIdOrder() {
    final var images = new java.util.ArrayList<>(List.of(image("a"), image("b"), image("c")));
    when(this.imageRepository.findAllByRepoId(REPO_ID)).thenReturn(List.copyOf(images));
    images.forEach(image -> when(this.finder.findUntagged(image.getId())).thenReturn(List.of()));

    this.service.deleteUntagged(REPO, null);

    final var expected = images.stream().map(Image::getId).sorted().toList();
    final var captor = org.mockito.ArgumentCaptor.forClass(UUID.class);
    verify(this.imageService, times(3)).lockImage(captor.capture());
    assertThat(captor.getAllValues()).containsExactlyElementsOf(expected);
  }
}
