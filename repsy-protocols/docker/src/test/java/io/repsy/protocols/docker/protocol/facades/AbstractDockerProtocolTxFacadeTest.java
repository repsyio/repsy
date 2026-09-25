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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
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
import io.repsy.protocols.docker.shared.tag.dtos.BaseManifestDetail;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.BaseParsedPath;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import io.repsy.protocols.shared.utils.BaseUrlParserProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  private static final String UPLOAD_STORAGE_PATH = REPO_ID + "/blobs/upload-id";

  private TestFacade facade() {
    return new TestFacade(
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

  private void uploadHolds(final byte[] bytes) {
    when(this.dockerStorageService.getResource(
            argThat(path -> path != null && path.getPath().equals(UPLOAD_STORAGE_PATH)),
            eq(REPO_NAME)))
        .thenReturn(Optional.of(new ByteArrayResource(bytes)));
  }

  private static String sha256Of(final byte[] bytes) throws NoSuchAlgorithmException {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  @Test
  @DisplayName("uploadLayerChunk() appends the chunk and reports the size of the whole upload")
  void uploadLayerChunkAppendsAndReportsTheUploadSize() throws Exception {
    final var context = newContext();
    when(this.dockerStorageService.appendInputStreamToPath(
            eq(REPO_NAME),
            argThat(path -> path != null && path.getPath().equals(UPLOAD_STORAGE_PATH)),
            any()))
        .thenReturn(BaseUsages.ofDisk(100));
    this.uploadHolds(new byte[350]);

    final var size =
        this.facade()
            .uploadLayerChunk(
                context,
                new RelativePath("/blobs/upload-id"),
                new ByteArrayInputStream(new byte[100]),
                100);

    assertThat(size).isEqualTo(350);
    assertThat(context.<BaseUsages>getProperty("usages").getDiskUsage()).isEqualTo(100);
    verify(this.dockerStorageService, never()).writeInputStreamToPath(any(), any(), any());
  }

  @Test
  @DisplayName("verifyLayerDigest() accepts an upload that hashes to the claimed digest")
  void verifyLayerDigestAcceptsAMatch() throws Exception {
    final var bytes = "layer bytes".getBytes(StandardCharsets.UTF_8);
    this.uploadHolds(bytes);

    assertThatCode(
            () ->
                this.facade()
                    .verifyLayerDigest(
                        newContext(), new RelativePath("/blobs/upload-id"), sha256Of(bytes)))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("verifyLayerDigest() refuses an upload that hashes to another digest")
  void verifyLayerDigestRefusesAMismatch() throws Exception {
    this.uploadHolds("layer bytes".getBytes(StandardCharsets.UTF_8));
    final var claimed = sha256Of("other bytes".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(
            () ->
                this.facade()
                    .verifyLayerDigest(newContext(), new RelativePath("/blobs/upload-id"), claimed))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("digestMismatch");
  }

  @Test
  @DisplayName("finalizeLayerUpload() sizes the layer from the uploaded file and saves it")
  void finalizeLayerUploadRecordsTheUploadedSize() throws Exception {
    final var context = newContext();
    final var uploadPath = new RelativePath("/blobs/upload-id");
    final var layerInfo = LayerInfo.builder().uuid(UUID.randomUUID()).digest("sha256:abc").build();
    this.uploadHolds(new byte[512]);

    this.facade().finalizeLayerUpload(context, uploadPath, layerInfo);

    assertThat(layerInfo.getSize()).isEqualTo(512);
    verify(this.layerService).update(layerInfo, REPO_ID);
  }

  @Test
  @DisplayName("getUploadSize() reports the size of the bytes written so far")
  void getUploadSizeReportsTheWrittenSize() throws Exception {
    this.uploadHolds(new byte[257]);

    final var size =
        this.facade().getUploadSize(newContext(), new RelativePath("/blobs/upload-id"));

    assertThat(size).isEqualTo(257);
  }

  @Test
  @DisplayName("getUploadSize() refuses an upload session that was never written")
  void getUploadSizeRefusesAMissingSession() {
    when(this.dockerStorageService.getResource(
            argThat(path -> path != null && path.getPath().equals(UPLOAD_STORAGE_PATH)),
            eq(REPO_NAME)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> this.facade().getUploadSize(newContext(), new RelativePath("/blobs/upload-id")))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("resourceNotFound");
  }

  // ---------------------------------------------------------------------------------------------
  // getManifest() -- RPS-1215: this is the single resolution path both GET and HEAD share, so its
  // sha256 short-circuit (resolveManifestDigest) is exercised here rather than through the now
  // -removed findTagAndManifest().
  // ---------------------------------------------------------------------------------------------

  private static final String IMAGE_NAME = "app";

  private BaseImageInfo<UUID> stubImage() {
    final var imageInfo =
        BaseImageInfo.<UUID>builder().id(UUID.randomUUID()).name(IMAGE_NAME).build();
    when(this.imageService.findImageInfoByRepoIdAndName(REPO_ID, IMAGE_NAME)).thenReturn(imageInfo);
    return imageInfo;
  }

  private void stubManifestStorage(final byte[] body) {
    when(this.dockerStorageService.getResource(
            argThat(path -> path != null && path.getPath().contains("manifests/")), eq(REPO_NAME)))
        .thenReturn(Optional.of(new ByteArrayResource(body)));
  }

  @Test
  @DisplayName("getManifest() resolves a tag reference through the active-tag lookup")
  void getManifestResolvesATagReferenceThroughTheActiveTagLookup() throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var digest = sha256Of("manifest-body".getBytes(StandardCharsets.UTF_8));
    when(this.manifestService.findActiveTagByNameAndRepoAndImage(REPO_ID, IMAGE_NAME, "latest"))
        .thenReturn(Optional.of(BaseTagDetail.<UUID>builder().digest(digest).build()));
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest(digest);
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, digest))
        .thenReturn(manifestDetail);
    this.stubManifestStorage("manifest-body".getBytes(StandardCharsets.UTF_8));

    final var result =
        this.facade().getManifest(context, "latest", IMAGE_NAME, "/v2/images/app/manifests/latest");

    assertThat(result.mediaType()).isEqualTo("application/vnd.oci.image.manifest.v1+json");
    assertThat(result.digest()).isEqualTo(digest);
    assertThat(result.body()).isEqualTo("manifest-body");
    verify(this.manifestService).findActiveTagByNameAndRepoAndImage(REPO_ID, IMAGE_NAME, "latest");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName(
      "getManifest() short-circuits a digest reference of either algorithm straight to the "
          + "digest, skipping the tag lookup entirely")
  void getManifestShortCircuitsADigestReference(final String algorithm) throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var digest = algorithm + ":" + "a".repeat("sha256".equals(algorithm) ? 64 : 128);
    // The row's canonical digest is always the sha256 one; the answer echoes the reference.
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest("sha256:" + "c".repeat(64));
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, digest))
        .thenReturn(manifestDetail);
    this.stubManifestStorage("manifest-body".getBytes(StandardCharsets.UTF_8));

    final var result =
        this.facade()
            .getManifest(context, digest, IMAGE_NAME, "/v2/images/app/manifests/" + digest);

    assertThat(result.digest()).isEqualTo(digest);
    assertThat(result.body()).isEqualTo("manifest-body");
    verify(this.manifestService, never()).findActiveTagByNameAndRepoAndImage(any(), any(), any());
  }

  @Test
  @DisplayName(
      "getManifest() looks a digest reference up lower-cased and reports it, not the row's sha256,"
          + " so a sha512 request is answered with a sha512 digest (RPS-1244)")
  void getManifestReportsTheRequestedAlgorithm() throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var sha512 = "sha512:" + "ab".repeat(64);
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest("sha256:" + "c".repeat(64));
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, sha512))
        .thenReturn(manifestDetail);
    this.stubManifestStorage("manifest-body".getBytes(StandardCharsets.UTF_8));
    final var requested = "sha512:" + "AB".repeat(64);

    final var result =
        this.facade()
            .getManifest(context, requested, IMAGE_NAME, "/v2/images/app/manifests/" + requested);

    assertThat(result.digest()).isEqualTo(sha512);
  }

  @Test
  @DisplayName("getManifest() reads a manifest by its digest, wherever tags point (RPS-1216)")
  void getManifestReadsTheFileAtTheDigest() throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var digest = sha256Of("manifest-body".getBytes(StandardCharsets.UTF_8));
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest(digest);
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, digest))
        .thenReturn(manifestDetail);
    when(this.dockerStorageService.getResource(
            argThat(
                path -> path != null && path.getPath().equals(REPO_ID + "/manifests/" + digest)),
            eq(REPO_NAME)))
        .thenReturn(
            Optional.of(new ByteArrayResource("manifest-body".getBytes(StandardCharsets.UTF_8))));

    final var result =
        this.facade()
            .getManifest(context, digest, IMAGE_NAME, "/v2/images/app/manifests/" + digest);

    assertThat(result.body()).isEqualTo("manifest-body");
  }

  @Test
  @DisplayName(
      "getManifest() reads the legacy file named after the storage name until the repair service"
          + " has renamed it")
  void getManifestReadsALegacyFileThroughTheStorageName() throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var digest = sha256Of("manifest-body".getBytes(StandardCharsets.UTF_8));
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest(digest);
    manifestDetail.setStorageName("latest");
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, digest))
        .thenReturn(manifestDetail);
    final var legacyPath =
        REPO_ID + "/manifests/" + ManifestNameGenerator.generate(REPO_ID, IMAGE_NAME, "latest");
    when(this.dockerStorageService.existsResource(
            argThat(path -> path != null && path.getPath().equals(legacyPath)), eq(REPO_NAME)))
        .thenReturn(true);
    when(this.dockerStorageService.getResource(
            argThat(path -> path != null && path.getPath().equals(legacyPath)), eq(REPO_NAME)))
        .thenReturn(
            Optional.of(new ByteArrayResource("legacy-body".getBytes(StandardCharsets.UTF_8))));

    final var result =
        this.facade()
            .getManifest(context, digest, IMAGE_NAME, "/v2/images/app/manifests/" + digest);

    assertThat(result.body()).isEqualTo("legacy-body");
  }

  @Test
  @DisplayName(
      "getManifest() falls back to the digest file when the legacy file is already gone, so a"
          + " rename that beat the row update loses nothing")
  void getManifestFallsBackToTheDigestFile() throws Exception {
    final var context = newContext();
    final var imageInfo = this.stubImage();
    final var digest = sha256Of("manifest-body".getBytes(StandardCharsets.UTF_8));
    final var manifestDetail = new BaseManifestDetail<UUID>();
    manifestDetail.setMediaType("application/vnd.oci.image.manifest.v1+json");
    manifestDetail.setDigest(digest);
    manifestDetail.setStorageName("latest");
    when(this.manifestService.findManifestByRepoIdAndImageNameAndDigest(REPO_ID, imageInfo, digest))
        .thenReturn(manifestDetail);
    when(this.dockerStorageService.existsResource(any(), eq(REPO_NAME))).thenReturn(false);
    when(this.dockerStorageService.getResource(
            argThat(
                path -> path != null && path.getPath().equals(REPO_ID + "/manifests/" + digest)),
            eq(REPO_NAME)))
        .thenReturn(
            Optional.of(new ByteArrayResource("manifest-body".getBytes(StandardCharsets.UTF_8))));

    final var result =
        this.facade()
            .getManifest(context, digest, IMAGE_NAME, "/v2/images/app/manifests/" + digest);

    assertThat(result.body()).isEqualTo("manifest-body");
  }

  private static ManifestForm formFor(final String reference, final byte[] bytes)
      throws NoSuchAlgorithmException {
    return ManifestForm.builder()
        .tagName(reference)
        .contentType("application/vnd.oci.image.manifest.v1+json")
        .manifestJson(new String(bytes, StandardCharsets.UTF_8))
        .digest(DockerDigestCalculator.calculateDigest(bytes))
        .digestSha512(DockerDigestCalculator.calculateSha512Digest(bytes))
        .manifestBytes(bytes)
        .relativePath(new RelativePath("manifests/x"))
        .build();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName(
      "saveManifest() refuses a digest reference that is not the manifest's own digest, of either"
          + " algorithm, before anything is written")
  void saveManifestRefusesAWrongDigestReference(final String algorithm) throws Exception {
    final var reference = algorithm + ":" + "0".repeat("sha256".equals(algorithm) ? 64 : 128);
    final var form = formFor(reference, "{}".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> this.facade().saveManifest(newContext(), IMAGE_NAME, form))
        .isInstanceOf(BadRequestException.class)
        .hasMessage("digestMismatch");
    verify(this.dockerStorageService, never()).writeInputStreamToPath(any(), any(), any());
    verify(this.imageService, never()).findOrCreateImage(any(), any());
  }

  @Test
  @DisplayName(
      "saveManifest() refuses to move an existing tag to another manifest while overriding is off")
  void saveManifestRefusesAnOverrideWhenItIsOff() throws Exception {
    final var bytes = "{}".getBytes(StandardCharsets.UTF_8);
    final var context = newContext();
    final var repoInfo =
        io.repsy.protocols.shared.utils.ProtocolContextUtils.<UUID>getRepoInfo(context);
    repoInfo.setAllowOverride(false);
    when(this.manifestService.findActiveTagByNameAndRepoAndImage(REPO_ID, IMAGE_NAME, "latest"))
        .thenReturn(
            Optional.of(BaseTagDetail.<UUID>builder().digest("sha256:" + "1".repeat(64)).build()));

    assertThatThrownBy(
            () -> this.facade().saveManifest(context, IMAGE_NAME, formFor("latest", bytes)))
        .isInstanceOf(AccessNotAllowedException.class)
        .hasMessage("packageOverrideDisabled");
    verify(this.dockerStorageService, never()).writeInputStreamToPath(any(), any(), any());
    verify(this.imageService, never()).findOrCreateImage(any(), any());
  }

  @Test
  @DisplayName("getManifest() refuses a tag reference that resolves to no active tag")
  void getManifestRefusesAnUnknownTagReference() {
    final var context = newContext();
    this.stubImage();
    when(this.manifestService.findActiveTagByNameAndRepoAndImage(REPO_ID, IMAGE_NAME, "missing"))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                this.facade()
                    .getManifest(
                        context, "missing", IMAGE_NAME, "/v2/images/app/manifests/missing"))
        .isInstanceOf(ItemNotFoundException.class)
        .hasMessage("tagNotFound");
  }
}
