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
package io.repsy.os.server.protocols.docker.protocol.facades;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestDeletionComponent;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("DockerProtocolTxFacade.deleteManifest")
class DockerProtocolTxFacadeTest {

  private static final String IMAGE = "app";
  private static final String REFERENCE = "sha256:" + "ab".repeat(32);

  private final ManifestDeletionComponent deletion = mock(ManifestDeletionComponent.class);

  @SuppressWarnings("unchecked")
  private final DockerProtocolTxFacade facade =
      new DockerProtocolTxFacade(
          mock(ImageService.class),
          mock(LayerService.class),
          mock(ManifestService.class),
          mock(DockerStorageService.class),
          JsonMapper.builder().build(),
          this.deletion);

  private final RepoInfo repoInfo =
      RepoInfo.builder().storageKey(UUID.randomUUID()).name("docker").build();

  private ProtocolContext context() {
    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName("docker")
            .relativePath(new RelativePath("/app/manifests/" + REFERENCE))
            .repoInfo(this.repoInfo)
            .build());
    return context;
  }

  @Test
  @DisplayName("refunds the freed manifest bytes as a negative disk usage")
  void refundsTheFreedBytes() {
    final var context = this.context();
    when(this.deletion.delete(this.repoInfo, IMAGE, REFERENCE)).thenReturn(700L);

    this.facade.deleteManifest(context, IMAGE, REFERENCE);

    assertThat(context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(-700L);
  }

  @Test
  @DisplayName("adds no usage when no file was freed")
  void addsNoUsageWhenNothingWasFreed() {
    final var context = this.context();
    when(this.deletion.delete(this.repoInfo, IMAGE, "latest")).thenReturn(0L);

    this.facade.deleteManifest(context, IMAGE, "latest");

    assertThat(context.<BaseUsages>getProperty("usages")).isNull();
  }

  @Test
  @DisplayName("adds no usage when the deletion fails")
  void addsNoUsageOnFailure() {
    final var context = this.context();
    when(this.deletion.delete(this.repoInfo, IMAGE, REFERENCE))
        .thenThrow(new ItemNotFoundException("manifestNotFound"));

    assertThatThrownBy(() -> this.facade.deleteManifest(context, IMAGE, REFERENCE))
        .isInstanceOf(ItemNotFoundException.class);
    assertThat(context.<BaseUsages>getProperty("usages")).isNull();
  }
}
