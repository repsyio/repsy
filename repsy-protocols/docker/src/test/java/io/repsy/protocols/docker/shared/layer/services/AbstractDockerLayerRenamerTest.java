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
package io.repsy.protocols.docker.shared.layer.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerLayerRenamer.renameLayers")
class AbstractDockerLayerRenamerTest {

  private static final UUID STORAGE_KEY = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "images";

  @Mock private DockerStorageService<UUID> dockerStorageService;
  @Mock private LayerService<UUID> layerService;

  private static class TestRenamer extends AbstractDockerLayerRenamer<UUID> {

    TestRenamer(final DockerStorageService<UUID> storage, final LayerService<UUID> layers) {
      super(storage, layers, JsonMapper.builder().build());
    }
  }

  private static BaseRepoInfo<UUID> repoInfo() {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(STORAGE_KEY);
    repoInfo.setStorageKey(STORAGE_KEY);
    repoInfo.setName(REPO_NAME);
    return repoInfo;
  }

  private static LayerInfo layer(final String digest) {
    return LayerInfo.builder().uuid(UUID.randomUUID()).digest(digest).build();
  }

  private static StoragePath uuidPath(final LayerInfo layer) {
    return StoragePath.of(STORAGE_KEY, "blobs/" + layer.getUuid());
  }

  private AbstractDockerLayerRenamer<UUID> renamer() {
    return new TestRenamer(this.dockerStorageService, this.layerService);
  }

  @Test
  @DisplayName("sums the usage refunded by every duplicate legacy layer it drops")
  void sumsRefundsOfDroppedDuplicates() {
    final var first = layer("sha256:aaa");
    final var second = layer("sha256:bbb");
    final var third = layer("sha256:ccc");
    final var storageMap = new LinkedHashMap<LayerInfo, StoragePath>();
    for (final var layer : List.of(first, second, third)) {
      storageMap.put(layer, uuidPath(layer));
    }
    when(this.dockerStorageService.existsResource(any(StoragePath.class), eq(REPO_NAME)))
        .thenReturn(true);
    when(this.dockerStorageService.rename(
            eq(STORAGE_KEY), any(RelativePath.class), eq("sha256:aaa")))
        .thenReturn(BaseUsages.ofDisk(-120));
    when(this.dockerStorageService.rename(
            eq(STORAGE_KEY), any(RelativePath.class), eq("sha256:bbb")))
        .thenReturn(BaseUsages.ofDisk(0));
    when(this.dockerStorageService.rename(
            eq(STORAGE_KEY), any(RelativePath.class), eq("sha256:ccc")))
        .thenReturn(BaseUsages.ofDisk(-30));

    final var usages = this.renamer().renameLayers(repoInfo(), storageMap);

    assertThat(usages.getDiskUsage()).isEqualTo(-150);
  }

  @Test
  @DisplayName("reports no usage change when every layer is only renamed")
  void reportsNothingWhenNothingIsDropped() {
    final var layer = layer("sha256:aaa");
    when(this.dockerStorageService.existsResource(any(StoragePath.class), eq(REPO_NAME)))
        .thenReturn(true);
    when(this.dockerStorageService.rename(eq(STORAGE_KEY), any(RelativePath.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    final var usages = this.renamer().renameLayers(repoInfo(), Map.of(layer, uuidPath(layer)));

    assertThat(usages.getDiskUsage()).isZero();
  }

  @Test
  @DisplayName("leaves a layer that is on no disk alone and reports no usage change")
  void skipsLayersMissingFromStorage() {
    final var layer = layer("sha256:aaa");
    when(this.dockerStorageService.existsResource(any(StoragePath.class), eq(REPO_NAME)))
        .thenReturn(false);

    final var usages = this.renamer().renameLayers(repoInfo(), Map.of(layer, uuidPath(layer)));

    assertThat(usages.getDiskUsage()).isZero();
    verify(this.dockerStorageService, never()).rename(any(), any(), any());
  }

  @Test
  @DisplayName("reports no usage change for an empty layer set")
  void emptyLayerSet() {
    assertThat(this.renamer().renameLayers(repoInfo(), Map.of()).getDiskUsage()).isZero();
  }
}
