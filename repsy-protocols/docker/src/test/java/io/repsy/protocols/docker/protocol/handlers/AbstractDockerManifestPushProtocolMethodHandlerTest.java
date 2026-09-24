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
package io.repsy.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.docker.protocol.DockerProtocolProvider;
import io.repsy.protocols.docker.protocol.facades.DockerProtocolFacade;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerManifestPushProtocolMethodHandler")
class AbstractDockerManifestPushProtocolMethodHandlerTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "images";
  private static final String MANIFEST_TYPE = "application/vnd.oci.image.manifest.v1+json";
  private static final String INDEX_TYPE = "application/vnd.oci.image.index.v1+json";
  private static final String MANIFEST_JSON =
      "{\"schemaVersion\":2,\"config\":{\"digest\":\"sha256:c\"},"
          + "\"layers\":[{\"digest\":\"sha256:l\"}]}";
  private static final String INDEX_JSON =
      "{\"schemaVersion\":2,\"manifests\":[{\"digest\":\"sha256:m\",\"size\":9,"
          + "\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}";

  @Mock private PathParser basePathParser;
  @Mock private DockerProtocolFacade<UUID> dockerFacade;
  @Mock private DockerProtocolProvider provider;
  @Mock private AbstractDockerLayerRenamer<UUID> layerRenamer;
  @Mock private ImageService<UUID> imageService;

  private static class TestHandler extends AbstractDockerManifestPushProtocolMethodHandler<UUID> {

    TestHandler(
        final PathParser basePathParser,
        final DockerProtocolFacade<UUID> dockerFacade,
        final DockerProtocolProvider provider,
        final AbstractDockerLayerRenamer<UUID> layerRenamer,
        final ImageService<UUID> imageService) {
      super(basePathParser, dockerFacade, provider, layerRenamer, imageService);
    }

    @Override
    public BaseParsedPath parseForManifest(final String servletPath, final String fileName) {
      return BaseParsedPath.builder()
          .imageName("app")
          .repoName(REPO_NAME)
          .relativePath(new RelativePath("/manifests/" + fileName))
          .build();
    }

    @Override
    protected String getServletURILocation(
        final ProtocolContext context, final String imageName, final String digest) {
      return "/v2/" + REPO_NAME + "/" + imageName + "/manifests/" + digest;
    }
  }

  private AbstractDockerManifestPushProtocolMethodHandler<UUID> handler() {
    return new TestHandler(
        this.basePathParser,
        this.dockerFacade,
        this.provider,
        this.layerRenamer,
        this.imageService);
  }

  private static ProtocolContext context() {
    return context(MANIFEST_JSON);
  }

  private static ProtocolContext context(final String manifestJson) {
    return context(manifestJson, "latest");
  }

  private static ProtocolContext context(final String manifestJson, final String reference) {
    final var repoInfo = new BaseRepoInfo<UUID>();
    repoInfo.setId(REPO_ID);
    repoInfo.setStorageKey(REPO_ID);
    repoInfo.setName(REPO_NAME);

    final var context = new ProtocolContext();
    context.addProperty(
        "urlProperties",
        BaseUrlParserProperties.<UUID, BaseRepoInfo<UUID>>builder()
            .repoName(REPO_NAME)
            .relativePath(new RelativePath("/app/manifests/" + reference))
            .repoInfo(repoInfo)
            .build());
    context.addProperty("manifestJson", manifestJson);
    return context;
  }

  private static MockHttpServletRequest request(final String contentType) {
    return request(contentType, "latest");
  }

  private static MockHttpServletRequest request(final String contentType, final String reference) {
    final var path = "/v2/images/app/manifests/" + reference;
    final var request = new MockHttpServletRequest("PUT", path);
    request.setServletPath(path);
    request.addHeader("Content-Type", contentType);
    return request;
  }

  private void stubImageAndSave(final ProtocolContext context, final BaseUsages manifestUsage)
      throws Exception {
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenAnswer(
            invocation -> {
              if (manifestUsage != null) {
                context.addProperty("usages", manifestUsage);
              }
              return "sha256:manifest";
            });
  }

  private long usageOf(final ProtocolContext context) {
    return context.<BaseUsages>getProperty("usages").getDiskUsage();
  }

  @Test
  @DisplayName("nets the usage refunded for a dropped legacy layer against the manifest's own")
  void netsLayerRefundAgainstManifestUsage() throws Exception {
    final var context = context();
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));
    final var layerMap = Map.<LayerInfo, StoragePath>of();
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(layerMap);
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), eq(layerMap)))
        .thenReturn(BaseUsages.ofDisk(-1500));

    final var response =
        this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(this.usageOf(context)).isEqualTo(-1100);
  }

  @Test
  @DisplayName("reports the refund alone when the manifest was already stored and added no usage")
  void refundsWhenManifestAddedNoUsage() throws Exception {
    final var context = context();
    this.stubImageAndSave(context, null);
    final var layerMap = Map.<LayerInfo, StoragePath>of();
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(layerMap);
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), eq(layerMap)))
        .thenReturn(BaseUsages.ofDisk(-1500));

    this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(this.usageOf(context)).isEqualTo(-1500);
  }

  @Test
  @DisplayName("leaves the manifest's usage untouched when no layer was dropped")
  void keepsManifestUsageWhenNothingDropped() throws Exception {
    final var context = context();
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(this.usageOf(context)).isEqualTo(400);
  }

  @Test
  @DisplayName("does not rename layers for an image index, which references manifests only")
  void indexDoesNotRenameLayers() throws Exception {
    final var context = context(INDEX_JSON);
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));

    final var response =
        this.handler().handle(context, request(INDEX_TYPE), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(this.usageOf(context)).isEqualTo(400);
    verify(this.layerRenamer, never()).renameLayers(any(), any());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName(
      "answers a push by a digest reference with that digest, in its algorithm, in"
          + " Docker-Content-Digest and Location (RPS-1244)")
  void answersADigestPushInTheAlgorithmOfTheReference(final String algorithm) throws Exception {
    // Upper-case hex: the answer is the normalized (lower-cased) reference.
    final var reference = algorithm + ":" + "AB".repeat("sha256".equals(algorithm) ? 32 : 64);
    final var context = context(MANIFEST_JSON, reference);
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));
    final var expected = reference.toLowerCase(Locale.ROOT);

    final var response =
        this.handler()
            .handle(context, request(MANIFEST_TYPE, reference), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst("Docker-Content-Digest")).isEqualTo(expected);
    assertThat(response.getHeaders().getFirst("Location"))
        .isEqualTo("/v2/images/app/manifests/" + expected);
  }

  @Test
  @DisplayName("answers a push by tag with the canonical sha256 digest")
  void answersATagPushWithTheSha256Digest() throws Exception {
    final var context = context();
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    final var response =
        this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(response.getHeaders().getFirst("Docker-Content-Digest"))
        .isEqualTo("sha256:manifest");
  }

  @Test
  @DisplayName("refuses a malformed manifest before the image is created or anything is stored")
  void refusesMalformedManifestBeforeStoringAnything() {
    final var context = context("{}");

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestConfigMissing");

    verifyNoInteractions(this.imageService, this.dockerFacade, this.layerRenamer);
  }

  @Test
  @DisplayName("reads the manifest from the request body when the context has none")
  void refusesMalformedManifestReadFromTheBody() {
    final var context = context("");
    final var request = request(INDEX_TYPE);
    request.setContent("not json".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> this.handler().handle(context, request, new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestInvalidJson");

    verifyNoInteractions(this.imageService, this.dockerFacade, this.layerRenamer);
  }

  @Test
  @DisplayName("refreshes the image's size and digest after a single-platform push (RPS-1314)")
  void refreshesTheImageAfterASinglePlatformPush() throws Exception {
    final var context = context();
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    verify(this.imageService).refreshImageSize(eq(REPO_ID), any(UUID.class));
  }

  @Test
  @DisplayName("refreshes the image's size and digest after an index push (RPS-1314)")
  void refreshesTheImageAfterAnIndexPush() throws Exception {
    final var context = context(INDEX_JSON);
    this.stubImageAndSave(context, BaseUsages.ofDisk(400));

    this.handler().handle(context, request(INDEX_TYPE), new MockHttpServletResponse());

    verify(this.imageService).refreshImageSize(eq(REPO_ID), any(UUID.class));
  }

  @Test
  @DisplayName("runs the whole save again when it loses a unique-index race (RPS-1314)")
  void retriesTheSaveThatLostARace() throws Exception {
    final var context = context();
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenThrow(new DataIntegrityViolationException("ux_docker_manifest__image_id_digest"))
        .thenReturn("sha256:manifest");
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    final var response =
        this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(this.dockerFacade, times(2))
        .saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class));
  }

  @Test
  @DisplayName("runs the whole save again when it loses an optimistic-lock race (RPS-1322)")
  void retriesTheSaveThatLostAVersionCheck() throws Exception {
    final var context = context();
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenThrow(new OptimisticLockingFailureException("docker_tag version"))
        .thenReturn("sha256:manifest");
    when(this.layerRenamer.findLayersToRename(any(BaseRepoInfo.class), eq(MANIFEST_JSON)))
        .thenReturn(Map.of());
    when(this.layerRenamer.renameLayers(any(BaseRepoInfo.class), any()))
        .thenReturn(BaseUsages.ofDisk(0));

    final var response =
        this.handler().handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    verify(this.dockerFacade, times(2))
        .saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class));
  }

  @Test
  @DisplayName("gives up after three optimistic-lock failures")
  void givesUpAfterThreeVersionCheckFailures() throws Exception {
    final var context = context();
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenThrow(new OptimisticLockingFailureException("still stale"));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse()))
        .isInstanceOf(OptimisticLockingFailureException.class);

    verify(this.dockerFacade, times(3))
        .saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class));
    verifyNoInteractions(this.layerRenamer);
  }

  @Test
  @DisplayName("gives up after three attempts and does not touch the image or the layers")
  void givesUpAfterThreeAttempts() throws Exception {
    final var context = context();
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenThrow(new DataIntegrityViolationException("still failing"));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse()))
        .isInstanceOf(DataIntegrityViolationException.class);

    verify(this.dockerFacade, times(3))
        .saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class));
    verifyNoInteractions(this.layerRenamer);
    verify(this.imageService, never()).refreshImageSize(any(), any());
  }

  @Test
  @DisplayName("does not retry a failure that is not a lost race")
  void doesNotRetryOtherFailures() throws Exception {
    final var context = context();
    final var imageInfo = BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name("app").build();
    when(this.imageService.findOrCreateImage(REPO_ID, "app")).thenReturn(imageInfo);
    when(this.dockerFacade.saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class)))
        .thenThrow(new BadRequestException("digestMismatch"));

    assertThatThrownBy(
            () ->
                this.handler()
                    .handle(context, request(MANIFEST_TYPE), new MockHttpServletResponse()))
        .isInstanceOf(BadRequestException.class);

    verify(this.dockerFacade).saveManifest(eq(context), eq(imageInfo), any(ManifestForm.class));
  }
}
