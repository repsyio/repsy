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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * RPS-1242: a blob or manifest referenced by a {@code sha512:} digest is routed like a {@code
 * sha256:} one. {@code BlobDigests.isSupported} (and so the push guards and the blob finalize)
 * accept both algorithms, but the path dispatch only recognized {@code sha256:}, so a sha512
 * reference was parsed as a tag and rejected.
 *
 * <p>RPS-1244: a manifest is first-class in both algorithms. It is stored with its {@code sha256}
 * and its {@code sha512} digest, so it is addressable by either, and the response reports the
 * algorithm the client used ({@code Docker-Content-Digest} and {@code Location}): a reference by
 * {@code sha512} gets the {@code sha512} digest back, a tag or a {@code sha256} reference keeps the
 * {@code sha256} one. A {@code sha512:} reference never becomes a tag row.
 */
@DisplayName("Docker sha512 digest references")
class DockerSha512ReferenceIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private TagRepository tagRepository;

  private DockerWire wire;

  @BeforeEach
  void setUpWire() {
    this.wire =
        new DockerWire(
            this.mockMvc,
            this.webApplicationContext,
            protocolPort(),
            this.adminProtocolBearerToken());
  }

  /** Pushes an image manifest by tag, and answers its bytes as the registry stored them. */
  private String pushByTag(final Repo repo, final String tag, final String layer) throws Exception {
    this.wire.pushBlobsOf(repo, IMAGE, layer);
    final var manifest = DockerWire.imageManifest(layer);
    final var push = this.wire.putImage(repo, IMAGE, tag, manifest);
    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);

    return manifest;
  }

  private static String digest(final String algorithm, final String jcaName, final byte[] bytes) {
    try {
      return algorithm
          + ":"
          + HexFormat.of().formatHex(MessageDigest.getInstance(jcaName).digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(final byte[] bytes) {
    return digest("sha256", "SHA-256", bytes);
  }

  private static String sha512(final byte[] bytes) {
    return digest("sha512", "SHA-512", bytes);
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private void pushBlob(final Repo repo, final byte[] blob, final String digest, final String token)
      throws Exception {
    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), IMAGE)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), IMAGE, uploadId)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
    assertThat(finalize.getHeader("Docker-Content-Digest")).isEqualTo(digest);
  }

  /**
   * Sends a manifest without the servlet filters: the manifest handler matches the {@code
   * Content-Type} header exactly, and the character-encoding filter would append {@code
   * ;charset=UTF-8} to it.
   */
  private MockHttpServletResponse putManifest(
      final Repo repo, final String reference, final String body, final String token)
      throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .contentType(OCI_MANIFEST)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  /** Pushes a config and a layer blob (sha256) and returns a manifest body that references them. */
  private String pushManifestBlobs(final Repo repo, final String token) throws Exception {
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, sha256(config), token);
    this.pushBlob(repo, layer, sha256(layer), token);

    return this.manifestBody(config, layer, sha256(layer));
  }

  private String manifestBody(final byte[] config, final byte[] layer, final String layerDigest) {
    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
        .formatted(
            OCI_MANIFEST,
            OCI_CONFIG,
            sha256(config),
            config.length,
            OCI_LAYER,
            layerDigest,
            layer.length);
  }

  private MockHttpServletResponse blobRequest(
      final boolean headOnly, final Repo repo, final String digest, final String token)
      throws Exception {
    final var path = "/v2/{repo}/{image}/blobs/{digest}";
    return this.mockMvc
        .perform(
            (headOnly
                    ? head(path, repo.getName(), IMAGE, digest)
                    : get(path, repo.getName(), IMAGE, digest))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private MockHttpServletResponse manifestRequest(
      final boolean headOnly, final Repo repo, final String reference, final String token)
      throws Exception {
    final var path = "/v2/{repo}/{image}/manifests/{reference}";
    return this.mockMvc
        .perform(
            (headOnly
                    ? head(path, repo.getName(), IMAGE, reference)
                    : get(path, repo.getName(), IMAGE, reference))
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("HEAD of a blob by its digest is 200 with that digest and the blob's length")
  void headOfBlobByDigest(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var blob = (algorithm + "-addressed-blob").getBytes(StandardCharsets.UTF_8);
    final var digest = "sha512".equals(algorithm) ? sha512(blob) : sha256(blob);
    this.pushBlob(repo, blob, digest, token);

    final var head = this.blobRequest(true, repo, digest, token);

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(digest);
    assertThat(head.getHeader("Content-Length")).isEqualTo(String.valueOf(blob.length));
  }

  @Test
  @DisplayName("HEAD of an unknown sha512 blob digest is 404")
  void unknownBlobShaFiveTwelveIs404() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();

    final var head = this.blobRequest(true, repo, "sha512:" + "0".repeat(128), token);

    assertThat(head.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a manifest pushed by its sha512 digest is served by HEAD and GET of that digest")
  void manifestByShaFiveTwelve() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = this.pushManifestBlobs(repo, token);
    final var reference = sha512(manifest.getBytes(StandardCharsets.UTF_8));

    final var push = this.putManifest(repo, reference, manifest, token);
    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);
    assertThat(push.getHeader("Docker-Content-Digest")).isEqualTo(reference);
    assertThat(push.getHeader("Location")).endsWith("/manifests/" + reference);

    final var head = this.manifestRequest(true, repo, reference, token);
    final var get = this.manifestRequest(false, repo, reference, token);

    assertThat(head.getStatus()).as(head.getContentAsString()).isEqualTo(200);
    assertThat(get.getStatus()).as(get.getContentAsString()).isEqualTo(200);
    assertThat(get.getContentAsString()).isEqualTo(manifest);
    assertThat(get.getContentType()).isEqualTo(OCI_MANIFEST);
    assertThat(head.getHeader("Docker-Content-Digest")).isEqualTo(reference);
    assertThat(get.getHeader("Docker-Content-Digest")).isEqualTo(reference);
  }

  @Test
  @DisplayName(
      "a push by sha512 stores no tag: no tag row is named after the digest, and the panel lists none")
  void aShaFiveTwelvePushCreatesNoTag() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.pushByTag(repo, "latest", "layer-tag");
    final var byDigest = DockerWire.imageManifest("layer-digest");
    this.wire.pushBlobsOf(repo, IMAGE, "layer-digest");
    final var reference = sha512(DockerWire.bytes(byDigest));

    final var push = this.wire.putImage(repo, IMAGE, reference, byDigest);

    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    assertThat(this.tagRepository.findAllByImageRepoIdAndImageId(repo.getId(), image.getId()))
        .extracting(tag -> tag.getName())
        .containsExactly("latest");
    final var tags =
        this.expectSuccess(
            this.perform(
                get("/api/docker/images/%s/%s/tags".formatted(repo.getName(), IMAGE))
                    .header(AUTHORIZATION, this.adminBearerToken())),
            "imageTagsFetched",
            "Image tags fetched.");
    assertThat(JsonPath.<List<String>>read(tags, "$.data.content[*].name"))
        .containsExactly("latest");
    assertThat(this.manifestRepository.findAllByImageId(image.getId())).hasSize(2);
    assertThat(manifest).isNotEqualTo(byDigest);
  }

  @Test
  @DisplayName("a manifest pushed by tag is stored with both digests and served by either")
  void aManifestIsAddressableByBothDigests() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.pushByTag(repo, "latest", "layer-both");
    final var sha256 = sha256(DockerWire.bytes(manifest));
    final var sha512 = sha512(DockerWire.bytes(manifest));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();

    final var row = this.manifestRepository.findByImageIdAndAnyDigest(image.getId(), sha512);
    assertThat(row).isPresent();
    assertThat(row.get().getDigest()).isEqualTo(sha256);
    assertThat(row.get().getDigestSha512()).isEqualTo(sha512);

    final var token = this.adminProtocolBearerToken();

    for (final var headOnly : List.of(false, true)) {
      final var bySha256 = this.manifestRequest(headOnly, repo, sha256, token);
      final var bySha512 = this.manifestRequest(headOnly, repo, sha512, token);
      final var byTag = this.manifestRequest(headOnly, repo, "latest", token);

      assertThat(bySha256.getStatus()).isEqualTo(200);
      assertThat(bySha512.getStatus()).isEqualTo(200);
      assertThat(byTag.getStatus()).isEqualTo(200);
      assertThat(bySha256.getHeader("Docker-Content-Digest")).isEqualTo(sha256);
      assertThat(bySha512.getHeader("Docker-Content-Digest")).isEqualTo(sha512);
      assertThat(byTag.getHeader("Docker-Content-Digest")).isEqualTo(sha256);
      assertThat(bySha512.getHeader("Content-Length"))
          .isEqualTo(bySha256.getHeader("Content-Length"));
    }
    assertThat(this.wire.getManifest(repo, IMAGE, sha512).getContentAsString()).isEqualTo(manifest);
  }

  @Test
  @DisplayName("the push by tag answers with the sha256 digest, and a sha256 push with that digest")
  void aTagOrShaTwoFiftySixPushKeepsTheShaTwoFiftySixDigest() throws Exception {
    final var repo = this.dockerRepo();
    this.wire.pushBlobsOf(repo, IMAGE, "layer-sha256");
    final var manifest = DockerWire.imageManifest("layer-sha256");
    final var sha256 = sha256(DockerWire.bytes(manifest));

    final var byTag = this.wire.putImage(repo, IMAGE, "latest", manifest);
    final var bySha256 = this.wire.putImage(repo, IMAGE, sha256, manifest);

    assertThat(byTag.getHeader("Docker-Content-Digest")).isEqualTo(sha256);
    assertThat(byTag.getHeader("Location")).endsWith("/manifests/" + sha256);
    assertThat(bySha256.getStatus()).isEqualTo(201);
    assertThat(bySha256.getHeader("Docker-Content-Digest")).isEqualTo(sha256);
    assertThat(bySha256.getHeader("Location")).endsWith("/manifests/" + sha256);
  }

  @Test
  @DisplayName("hex digits of a sha512 reference are case-insensitive and echoed lower-cased")
  void upperCaseShaFiveTwelveReferenceIsEchoedLowerCased() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.pushByTag(repo, "latest", "layer-case");
    final var sha512 = sha512(DockerWire.bytes(manifest));
    final var upper = "sha512:" + sha512.substring("sha512:".length()).toUpperCase(Locale.ROOT);

    final var get = this.wire.getManifest(repo, IMAGE, upper);

    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(get.getHeader("Docker-Content-Digest")).isEqualTo(sha512);
    assertThat(this.wire.putImage(repo, IMAGE, upper, manifest).getHeader("Docker-Content-Digest"))
        .isEqualTo(sha512);
  }

  @Test
  @DisplayName("a well-formed sha512 that names no manifest of the image is 404, not a mismatch")
  void anUnknownShaFiveTwelveIs404() throws Exception {
    final var repo = this.dockerRepo();
    this.pushByTag(repo, "latest", "layer-known");

    final var get = this.wire.getManifest(repo, IMAGE, "sha512:" + "0".repeat(128));
    final var head = this.wire.headManifest(repo, IMAGE, "sha512:" + "0".repeat(128));

    assertThat(get.getStatus()).isEqualTo(404);
    assertThat(head.getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName(
      "an index may list its manifests by sha512 and is itself pushed and served by sha512")
  void anIndexOfShaFiveTwelveChildren() throws Exception {
    final var repo = this.dockerRepo();
    final var child = this.pushByTag(repo, "child", "layer-child");
    final var childSha512 = sha512(DockerWire.bytes(child));
    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}"
            .formatted(
                DockerWire.OCI_INDEX, OCI_MANIFEST, childSha512, DockerWire.bytes(child).length);
    final var indexSha512 = sha512(DockerWire.bytes(index));

    final var push = this.wire.putManifest(repo, IMAGE, indexSha512, DockerWire.OCI_INDEX, index);

    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);
    assertThat(push.getHeader("Docker-Content-Digest")).isEqualTo(indexSha512);
    final var get = this.wire.getManifest(repo, IMAGE, indexSha512);
    assertThat(get.getStatus()).isEqualTo(200);
    assertThat(get.getContentAsString()).isEqualTo(index);
    assertThat(get.getHeader("Docker-Content-Digest")).isEqualTo(indexSha512);
    assertThat(this.wire.getManifest(repo, IMAGE, childSha512).getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "an identical push fills a missing sha512 digest, so an old row becomes addressable by it")
  void anIdenticalPushFillsAMissingShaFiveTwelve() throws Exception {
    final var repo = this.dockerRepo();
    final var manifest = this.pushByTag(repo, "latest", "layer-legacy");
    final var sha512 = sha512(DockerWire.bytes(manifest));
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var row =
        this.manifestRepository.findByImageIdAndAnyDigest(image.getId(), sha512).orElseThrow();
    row.setDigestSha512(null);
    this.manifestRepository.saveAndFlush(row);
    assertThat(this.wire.getManifest(repo, IMAGE, sha512).getStatus()).isEqualTo(404);

    final var again = this.wire.putImage(repo, IMAGE, "latest", manifest);

    assertThat(again.getStatus()).isEqualTo(201);
    assertThat(this.wire.getManifest(repo, IMAGE, sha512).getStatus()).isEqualTo(200);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sha256", "sha512"})
  @DisplayName("a manifest pushed under a digest reference that is not its digest is refused")
  void manifestUnderWrongDigestIsRefused(final String algorithm) throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var manifest = this.pushManifestBlobs(repo, token);

    final var wrong = "sha512".equals(algorithm) ? "0".repeat(128) : "0".repeat(64);
    final var push = this.putManifest(repo, algorithm + ":" + wrong, manifest, token);

    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(400);
  }
}
