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
package io.repsy.protocols.docker.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerProtocolTxFacade")
class AbstractDockerProtocolTxFacadeTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "images";

  @Mock private DockerStorageService<UUID> dockerStorageService;
  @Mock private LayerService<UUID> layerService;
  @Mock private ImageService<UUID> imageService;
  @Mock private ManifestService<UUID> manifestService;

  private static class TestFacade extends AbstractDockerProtocolTxFacade<UUID> {

    TestFacade(
        final DockerStorageService<UUID> dockerStorageService,
        final LayerService<UUID> layerService,
        final ImageService<UUID> imageService,
        final ManifestService<UUID> manifestService) {
      super(
          dockerStorageService,
          layerService,
          imageService,
          manifestService,
          JsonMapper.builder().build());
    }

    @Override
    public BaseParsedPath parseForLayer(final String servletPath, final String digest) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BaseParsedPath parseForManifest(final String servletPath, final String fileName) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  @DisplayName("finalizeLayerUpload() sizes the layer from the uploaded file and saves it")
  void finalizeLayerUploadRecordsTheUploadedSize() throws Exception {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/"))
            .repoInfo(repoInfo)
            .build());
    final var uploadPath = new RelativePath("/blobs/upload-id");
    final var layerInfo = LayerInfo.builder().uuid(UUID.randomUUID()).digest("sha256:abc").build();
    when(this.dockerStorageService.getResource(
            argThat(path -> path != null && path.getPath().equals(REPO_ID + "/blobs/upload-id")),
            eq(REPO_NAME)))
        .thenReturn(Optional.of(new ByteArrayResource(new byte[512])));

    new TestFacade(
            this.dockerStorageService, this.layerService, this.imageService, this.manifestService)
        .finalizeLayerUpload(context, uploadPath, layerInfo);

    assertThat(layerInfo.getSize()).isEqualTo(512);
    verify(this.layerService).update(layerInfo, REPO_ID);
  }
}
