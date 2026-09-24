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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestFileView;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestFileService.ManifestFileRef;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ManifestFileService")
class ManifestFileServiceTest {

  private static final UUID REPO = UUID.randomUUID();
  private static final UUID IMAGE = UUID.randomUUID();
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  private final ManifestRepository manifestRepository = mock(ManifestRepository.class);
  private final ManifestFileService service = new ManifestFileService(this.manifestRepository);

  private record View(String digest, String storageName) implements ManifestFileView {

    @Override
    public String getDigest() {
      return this.digest;
    }

    @Override
    public String getStorageName() {
      return this.storageName;
    }
  }

  @Test
  @DisplayName("takes the references of an image's manifests from their file views")
  void findsTheRefsOfAnImage() {
    when(this.manifestRepository.findFileViewsByImageId(IMAGE))
        .thenReturn(
            List.of(new View(DIGEST, null), new View("sha256:" + "b".repeat(64), "latest")));

    assertThat(this.service.findRefsOfImage(IMAGE))
        .containsExactly(
            new ManifestFileRef(DIGEST, null),
            new ManifestFileRef("sha256:" + "b".repeat(64), "latest"));
  }

  @Test
  @DisplayName("a file goes only when no row of the repo has its digest any more")
  void keepsTheFileOfADigestAnotherRowStillHas() {
    when(this.manifestRepository.countByImageRepoIdAndDigest(REPO, DIGEST)).thenReturn(1L);

    assertThat(
            this.service.findUnreferencedFileNames(
                REPO, IMAGE, "app", List.of(new ManifestFileRef(DIGEST, null))))
        .isEmpty();
  }

  @Test
  @DisplayName("the file of a digest that no row has any more is deleted by its digest name")
  void deletesTheFileOfAnUnreferencedDigest() {
    when(this.manifestRepository.countByImageRepoIdAndDigest(REPO, DIGEST)).thenReturn(0L);

    assertThat(
            this.service.findUnreferencedFileNames(
                REPO, IMAGE, "app", List.of(new ManifestFileRef(DIGEST, null))))
        .containsExactly(DIGEST);
  }

  @Test
  @DisplayName("a legacy file goes with its row unless another row of the image uses its name")
  void deletesALegacyFileNoOtherRowUses() {
    when(this.manifestRepository.countByImageRepoIdAndDigest(REPO, DIGEST)).thenReturn(0L);
    when(this.manifestRepository.countByImageIdAndStorageName(IMAGE, "latest")).thenReturn(0L);
    when(this.manifestRepository.countByImageIdAndStorageName(IMAGE, "shared")).thenReturn(1L);
    final var legacy = ManifestNameGenerator.generate(REPO, "app", "latest");

    assertThat(
            this.service.findUnreferencedFileNames(
                REPO,
                IMAGE,
                "app",
                List.of(
                    new ManifestFileRef(DIGEST, "latest"), new ManifestFileRef(DIGEST, "shared"))))
        .containsExactly(DIGEST, legacy);
  }
}
