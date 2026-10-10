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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.services.ImageService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerInfo;
import io.repsy.protocols.docker.shared.layer.services.LayerService;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins what {@link AbstractDockerProtocolTxFacade} does with a pushed manifest's config (the
 * platform it is stored under) and with a layer download, the parts the class's other test leaves
 * open. RPS-2061: written before the facade is split by concern.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractDockerProtocolTxFacade manifest platform and layer lookup")
class DockerProtocolTxFacadeManifestPlatformTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final String REPO_NAME = "images";
  private static final String IMAGE_NAME = "app";
  private static final String CONFIG_DIGEST = "sha256:" + "a".repeat(64);
  private static final String LAYER_DIGEST = "sha256:" + "b".repeat(64);
  private static final String IMAGE_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String INDEX = "application/vnd.oci.image.index.v1+json";

  @Mock private DockerStorageService<UUID> dockerStorageService;
  @Mock private LayerService<UUID> layerService;
  @Mock private ImageService<UUID> imageService;
  @Mock private ManifestService<UUID> manifestService;

  private TestFacade facade;

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
      return BaseParsedPath.builder().relativePath(new RelativePath("blobs/" + digest)).build();
    }

    @Override
    public BaseParsedPath parseForManifest(final String servletPath, final String fileName) {
      return BaseParsedPath.builder()
          .relativePath(new RelativePath("manifests/" + fileName))
          .build();
    }

    @Override
    public void deleteManifest(
        final ProtocolContext context, final String imageName, final String reference) {
      throw new UnsupportedOperationException();
    }
  }

  @BeforeEach
  void setUp() {
    this.facade =
        new TestFacade(
            this.dockerStorageService, this.layerService, this.imageService, this.manifestService);
  }

  private static ProtocolContext newContext() {
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
    return context;
  }

  private static ManifestForm formFor(final String contentType, final String json) {
    final var bytes = json.getBytes(StandardCharsets.UTF_8);
    try {
      return ManifestForm.builder()
          .tagName("latest")
          .contentType(contentType)
          .manifestJson(json)
          .digest(DockerDigestCalculator.calculateDigest(bytes))
          .digestSha512(DockerDigestCalculator.calculateSha512Digest(bytes))
          .manifestBytes(bytes)
          .relativePath(new RelativePath("manifests/x"))
          .build();
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String imageManifest(final String configMediaType) {
    return "{\"schemaVersion\":2,\"mediaType\":\""
        + MANIFEST
        + "\",\"config\":{\"mediaType\":\""
        + configMediaType
        + "\",\"digest\":\""
        + CONFIG_DIGEST
        + "\",\"size\":2},\"layers\":[{\"mediaType\":\"application/x\",\"digest\":\""
        + LAYER_DIGEST
        + "\",\"size\":2}]}";
  }

  private void stubImage() {
    when(this.imageService.getOrCreateImage(REPO_ID, IMAGE_NAME))
        .thenReturn(BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name(IMAGE_NAME).build());
  }

  private void stubWrite() {
    when(this.dockerStorageService.writeInputStreamToPath(eq(REPO_NAME), any(), any()))
        .thenReturn(BaseUsages.ofDisk(7));
  }

  private LayerInfo stubConfigLayer() {
    final var layer = LayerInfo.builder().uuid(UUID.randomUUID()).digest(CONFIG_DIGEST).build();
    when(this.layerService.findLayerInfoByRepoIdAndDigest(REPO_ID, CONFIG_DIGEST))
        .thenReturn(Optional.of(layer));
    return layer;
  }

  private void stubBlob(final String name, final String body) {
    when(this.dockerStorageService.findResource(
            argThat(path -> path != null && path.getPath().equals(REPO_ID + "/blobs/" + name)),
            eq(REPO_NAME)))
        .thenReturn(Optional.of(new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8))));
  }

  private TagForm savedSinglePlatformTag() {
    final var captor = ArgumentCaptor.forClass(TagForm.class);
    verify(this.manifestService).createSinglePlatformManifest(eq(REPO_ID), any(), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("saveManifest() stores an image under the os/architecture of its config blob")
  void saveManifestStoresTheImagePlatformOfTheConfig() throws Exception {
    this.stubImage();
    this.stubWrite();
    this.stubConfigLayer();
    this.stubBlob(CONFIG_DIGEST, "{\"os\":\"linux\",\"architecture\":\"arm64\"}");
    final var context = newContext();

    final var saved =
        this.facade.saveManifest(
            context, IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG)));

    assertThat(this.savedSinglePlatformTag().getPlatform()).isEqualTo("linux/arm64");
    assertThat(saved.image().getName()).isEqualTo(IMAGE_NAME);
    assertThat(context.<String>getProperty("artifactName")).isEqualTo(IMAGE_NAME);
    assertThat(context.<String>getProperty("artifactVersion")).isEqualTo("latest");
    assertThat(context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(7);
  }

  @Test
  @DisplayName("saveManifest() reads a config blob that is stored under the layer uuid")
  void saveManifestReadsTheConfigUnderTheLayerUuid() throws Exception {
    this.stubImage();
    this.stubWrite();
    final var layer = this.stubConfigLayer();
    when(this.dockerStorageService.findResource(
            argThat(
                path -> path != null && path.getPath().equals(REPO_ID + "/blobs/" + CONFIG_DIGEST)),
            eq(REPO_NAME)))
        .thenReturn(Optional.empty());
    this.stubBlob(layer.getUuid().toString(), "{\"os\":\"linux\",\"architecture\":\"amd64\"}");

    this.facade.saveManifest(
        newContext(), IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG)));

    assertThat(this.savedSinglePlatformTag().getPlatform()).isEqualTo("linux/amd64");
  }

  @Test
  @DisplayName("saveManifest() stores an artifact whose config is no image config as unknown")
  void saveManifestStoresAnArtifactAsUnknown() throws Exception {
    this.stubImage();
    this.stubWrite();

    this.facade.saveManifest(
        newContext(),
        IMAGE_NAME,
        formFor(MANIFEST, imageManifest("application/vnd.cncf.helm.config.v1+json")));

    assertThat(this.savedSinglePlatformTag().getPlatform()).isEqualTo("unknown");
    verify(this.layerService, never()).findLayerInfoByRepoIdAndDigest(any(), any());
  }

  @Test
  @DisplayName(
      "saveManifest() stores an attestation (empty config) as unknown without a config read")
  void saveManifestStoresAnAttestationAsUnknown() throws Exception {
    this.stubImage();
    this.stubWrite();

    this.facade.saveManifest(
        newContext(),
        IMAGE_NAME,
        formFor(MANIFEST, imageManifest("application/vnd.oci.empty.v1+json")));

    assertThat(this.savedSinglePlatformTag().getPlatform()).isEqualTo("unknown");
  }

  @Test
  @DisplayName("saveManifest() refuses a config blob that is not JSON, writing nothing")
  void saveManifestRefusesAConfigThatIsNotJson() {
    this.stubImage();
    this.stubConfigLayer();
    this.stubBlob(CONFIG_DIGEST, "not json");

    assertThatThrownBy(
            () ->
                this.facade.saveManifest(
                    newContext(), IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestConfigInvalid");
    verify(this.dockerStorageService, never()).writeInputStreamToPath(any(), any(), any());
  }

  @Test
  @DisplayName("saveManifest() refuses a config blob without an architecture, writing nothing")
  void saveManifestRefusesAConfigWithoutArchitecture() {
    this.stubImage();
    this.stubConfigLayer();
    this.stubBlob(CONFIG_DIGEST, "{\"os\":\"linux\"}");

    assertThatThrownBy(
            () ->
                this.facade.saveManifest(
                    newContext(), IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestConfigInvalid");
    verify(this.dockerStorageService, never()).writeInputStreamToPath(any(), any(), any());
  }

  @Test
  @DisplayName("saveManifest() refuses a config blank os, writing nothing")
  void saveManifestRefusesABlankOs() {
    this.stubImage();
    this.stubConfigLayer();
    this.stubBlob(CONFIG_DIGEST, "{\"os\":\" \",\"architecture\":\"arm64\"}");

    assertThatThrownBy(
            () ->
                this.facade.saveManifest(
                    newContext(), IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG))))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestConfigInvalid");
  }

  @Test
  @DisplayName("saveManifest() refuses an image whose config layer is unknown")
  void saveManifestRefusesAnUnknownConfigLayer() {
    this.stubImage();
    when(this.layerService.findLayerInfoByRepoIdAndDigest(REPO_ID, CONFIG_DIGEST))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.facade.saveManifest(
                    newContext(), IMAGE_NAME, formFor(MANIFEST, imageManifest(IMAGE_CONFIG))))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("layerNotFound");
  }

  @Test
  @DisplayName("saveManifest() stores an index as Multiplatform after its manifests are checked")
  void saveManifestStoresAnIndexAsMultiplatform() throws Exception {
    this.stubImage();
    this.stubWrite();
    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\""
            + INDEX
            + "\",\"manifests\":[{\"mediaType\":\""
            + MANIFEST
            + "\",\"digest\":\""
            + LAYER_DIGEST
            + "\",\"size\":3}]}";

    this.facade.saveManifest(newContext(), IMAGE_NAME, formFor(INDEX, index));

    final var captor = ArgumentCaptor.forClass(TagForm.class);
    verify(this.manifestService)
        .verifyManifestsExist(eq(REPO_ID), any(), eq(List.of(LAYER_DIGEST)));
    verify(this.manifestService).createManifestList(eq(REPO_ID), any(), captor.capture());
    assertThat(captor.getValue().getPlatform()).isEqualTo("Multiplatform");
    verify(this.manifestService, never()).createSinglePlatformManifest(any(), any(), any());
  }

  @Test
  @DisplayName("saveManifest() refuses a content type the registry does not store")
  void saveManifestRefusesAnUnsupportedContentType() {
    this.stubImage();

    assertThatThrownBy(
            () -> this.facade.saveManifest(newContext(), IMAGE_NAME, formFor("text/plain", "{}")))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("manifestMediaTypeUnsupported");
  }

  @Test
  @DisplayName("getLayer() returns the blob stored at the layer digest")
  void getLayerReturnsTheBlobAtTheDigest() throws Exception {
    final var layer = LayerInfo.builder().uuid(UUID.randomUUID()).digest(LAYER_DIGEST).build();
    when(this.layerService.findLayerInfoByRepoIdAndDigest(REPO_ID, LAYER_DIGEST))
        .thenReturn(Optional.of(layer));
    when(this.dockerStorageService.existsResource(any(), eq(REPO_NAME))).thenReturn(true);
    this.stubBlob(LAYER_DIGEST, "layer-bytes");

    final var resource = this.facade.getLayer(newContext(), LAYER_DIGEST, "/v2/x/blobs/y");

    assertThat(resource.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("layer-bytes");
  }

  @Test
  @DisplayName("getLayer() answers layerNotFound when no row has the digest")
  void getLayerRefusesAnUnknownDigest() {
    when(this.layerService.findLayerInfoByRepoIdAndDigest(REPO_ID, LAYER_DIGEST))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> this.facade.getLayer(newContext(), LAYER_DIGEST, "/v2/x/blobs/y"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("layerNotFound");
  }

  @Test
  @DisplayName("getLayer() answers layerNotFound when the row exists but no file does")
  void getLayerRefusesAMissingFile() {
    final var layer = LayerInfo.builder().uuid(UUID.randomUUID()).digest(LAYER_DIGEST).build();
    when(this.layerService.findLayerInfoByRepoIdAndDigest(REPO_ID, LAYER_DIGEST))
        .thenReturn(Optional.of(layer));
    when(this.dockerStorageService.existsResource(any(), eq(REPO_NAME))).thenReturn(false);

    assertThatThrownBy(() -> this.facade.getLayer(newContext(), LAYER_DIGEST, "/v2/x/blobs/y"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("layerNotFound");
  }
}
