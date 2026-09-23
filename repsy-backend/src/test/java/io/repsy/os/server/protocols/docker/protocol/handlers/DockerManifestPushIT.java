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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Docker manifest push endpoint, through the real wire protocol: a manifest that cannot be
 * stored (not JSON, or without the config, layers or manifests list the registry reads) is the
 * client's mistake and is answered with a 400 that names what is wrong, not with a 500.
 *
 * <p>{@link UsageUpdateService} is mocked: it is {@code @Async}, so it cannot see this test's
 * uncommitted data.
 */
@DisplayName("Docker manifest push")
class DockerManifestPushIT extends AbstractIntegrationTest {

  private static final String IMAGE = "app";
  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String DOCKER_MANIFEST =
      "application/vnd.docker.distribution.manifest.v2+json";
  private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
  private static final String DOCKER_LIST =
      "application/vnd.docker.distribution.manifest.list.v2+json";
  private static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  private static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";
  private static final String DIGEST =
      "sha256:0000000000000000000000000000000000000000000000000000000000000000";

  private static final String INVALID_JSON = "manifestInvalidJson";
  private static final String INVALID_STRUCTURE = "manifestInvalid";
  private static final String CONFIG_MISSING = "manifestConfigMissing";
  private static final String LAYERS_INVALID = "manifestLayersInvalid";
  private static final String MANIFESTS_INVALID = "manifestListManifestsInvalid";
  private static final String CONFIG_INVALID = "manifestConfigInvalid";
  private static final String SCHEMA_VERSION_INVALID = "manifestSchemaVersionInvalid";
  private static final String MEDIA_TYPE_UNSUPPORTED = "manifestMediaTypeUnsupported";

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ManifestRepository manifestRepository;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static String sha256(final byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Stream<Arguments> malformedImageManifests() {
    final var config =
        "{\"mediaType\":\"" + OCI_CONFIG + "\",\"digest\":\"" + DIGEST + "\",\"size\":2}";
    final var layer =
        "{\"mediaType\":\"" + OCI_LAYER + "\",\"digest\":\"" + DIGEST + "\",\"size\":3}";

    return Stream.of(
            Arguments.of("not json", "not json", INVALID_JSON),
            Arguments.of("an empty body", "", INVALID_JSON),
            Arguments.of("a truncated document", "{\"schemaVersion\":2,", INVALID_JSON),
            Arguments.of("a JSON array", "[]", INVALID_JSON),
            Arguments.of("a JSON string", "\"manifest\"", INVALID_JSON),
            Arguments.of("JSON null", "null", INVALID_JSON),
            Arguments.of("an empty object", "{}", CONFIG_MISSING),
            Arguments.of(
                "no config", "{\"schemaVersion\":2,\"layers\":[" + layer + "]}", CONFIG_MISSING),
            Arguments.of(
                "a null config",
                "{\"schemaVersion\":2,\"config\":null,\"layers\":[" + layer + "]}",
                CONFIG_MISSING),
            Arguments.of(
                "a config without a digest",
                "{\"schemaVersion\":2,\"config\":{\"size\":2},\"layers\":[" + layer + "]}",
                CONFIG_MISSING),
            Arguments.of(
                "a config with a blank digest",
                "{\"schemaVersion\":2,\"config\":{\"digest\":\" \"},\"layers\":[" + layer + "]}",
                CONFIG_MISSING),
            Arguments.of(
                "no layers", "{\"schemaVersion\":2,\"config\":" + config + "}", LAYERS_INVALID),
            Arguments.of(
                "null layers",
                "{\"schemaVersion\":2,\"config\":" + config + ",\"layers\":null}",
                LAYERS_INVALID),
            Arguments.of(
                "a layer without a digest",
                "{\"schemaVersion\":2,\"config\":" + config + ",\"layers\":[{\"size\":3}]}",
                LAYERS_INVALID),
            Arguments.of(
                "a null layer",
                "{\"schemaVersion\":2,\"config\":" + config + ",\"layers\":[null]}",
                LAYERS_INVALID),
            Arguments.of(
                "layers that are not an array",
                "{\"schemaVersion\":2,\"config\":" + config + ",\"layers\":\"none\"}",
                INVALID_STRUCTURE),
            Arguments.of(
                "a config that is not an object",
                "{\"schemaVersion\":2,\"config\":\"cfg\",\"layers\":[" + layer + "]}",
                INVALID_STRUCTURE),
            Arguments.of(
                "a layer size that is not a number",
                "{\"schemaVersion\":2,\"config\":"
                    + config
                    + ",\"layers\":[{\"digest\":\""
                    + DIGEST
                    + "\",\"size\":\"big\"}]}",
                INVALID_STRUCTURE))
        .flatMap(
            arguments ->
                Stream.of(OCI_MANIFEST, DOCKER_MANIFEST)
                    .map(
                        type ->
                            Arguments.of(
                                arguments.get()[0] + " as " + type,
                                type,
                                arguments.get()[1],
                                arguments.get()[2])));
  }

  private static Stream<Arguments> malformedIndexes() {
    final var entry =
        "{\"mediaType\":\""
            + OCI_MANIFEST
            + "\",\"digest\":\""
            + DIGEST
            + "\",\"size\":9,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}";

    return Stream.of(
            Arguments.of("not json", "not json", INVALID_JSON),
            Arguments.of("an empty body", "", INVALID_JSON),
            Arguments.of("a JSON array", "[]", INVALID_JSON),
            Arguments.of("an empty object", "{}", MANIFESTS_INVALID),
            Arguments.of(
                "null manifests", "{\"schemaVersion\":2,\"manifests\":null}", MANIFESTS_INVALID),
            Arguments.of(
                "an image manifest instead of an index",
                "{\"schemaVersion\":2,\"config\":{\"digest\":\"" + DIGEST + "\"},\"layers\":[]}",
                MANIFESTS_INVALID),
            Arguments.of(
                "an entry without a digest",
                "{\"schemaVersion\":2,\"manifests\":[{\"size\":9,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}",
                MANIFESTS_INVALID),
            Arguments.of(
                "an entry without a size",
                "{\"schemaVersion\":2,\"manifests\":[{\"digest\":\""
                    + DIGEST
                    + "\",\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}",
                INVALID_STRUCTURE),
            Arguments.of(
                "a null entry",
                "{\"schemaVersion\":2,\"manifests\":[null," + entry + "]}",
                MANIFESTS_INVALID),
            Arguments.of(
                "manifests that are not an array",
                "{\"schemaVersion\":2,\"manifests\":\"none\"}",
                INVALID_STRUCTURE),
            Arguments.of(
                "an entry size that is not a number",
                "{\"schemaVersion\":2,\"manifests\":[{\"digest\":\""
                    + DIGEST
                    + "\",\"size\":\"big\",\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}",
                INVALID_STRUCTURE))
        .flatMap(
            arguments ->
                Stream.of(OCI_INDEX, DOCKER_LIST)
                    .map(
                        type ->
                            Arguments.of(
                                arguments.get()[0] + " as " + type,
                                type,
                                arguments.get()[1],
                                arguments.get()[2])));
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  /**
   * Sends a manifest through a {@link org.springframework.test.web.servlet.MockMvc} without the
   * servlet filters: the manifest handler matches the {@code Content-Type} header exactly, and the
   * character-encoding filter would append {@code ;charset=UTF-8} to it, which a real client (and
   * Tomcat) never sends.
   */
  private MockHttpServletResponse putManifest(
      final Repo repo, final String reference, final String contentType, final String body)
      throws Exception {
    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .contentType(contentType)
                .content(body.getBytes(StandardCharsets.UTF_8))
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void assertRejected(
      final MockHttpServletResponse response, final String msgId, final Repo repo)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"MANIFEST_INVALID\"")
        .contains("\"detail\":\"" + msgId + "\"");
    // Nothing of the rejected push is left behind: no image, no manifest file.
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE)).isEmpty();
    assertThat(storageDirOf(repo).resolve("manifests"))
        .satisfiesAnyOf(
            path -> assertThat(path).doesNotExist(),
            path -> assertThat(path.toFile().list()).isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedImageManifests")
  @DisplayName("an image manifest that is malformed is answered with a 400 that names the problem")
  void malformedImageManifestIsRefused(
      final String description, final String contentType, final String body, final String msgId)
      throws Exception {
    final var repo = this.dockerRepo();

    final var response = this.putManifest(repo, "latest", contentType, body);

    this.assertRejected(response, msgId, repo);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedIndexes")
  @DisplayName("an index that is malformed is answered with a 400 that names the problem")
  void malformedIndexIsRefused(
      final String description, final String contentType, final String body, final String msgId)
      throws Exception {
    final var repo = this.dockerRepo();

    final var response = this.putManifest(repo, "latest", contentType, body);

    this.assertRejected(response, msgId, repo);
  }

  @Test
  @DisplayName("a malformed manifest pushed by digest is refused the same way")
  void malformedManifestPushedByDigestIsRefused() throws Exception {
    final var repo = this.dockerRepo();

    final var response = this.putManifest(repo, DIGEST, OCI_MANIFEST, "{}");

    this.assertRejected(response, CONFIG_MISSING, repo);
  }

  @Test
  @DisplayName("a well formed manifest is still stored after the malformed ones are refused")
  void wellFormedManifestIsStored() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
            .formatted(
                OCI_MANIFEST,
                OCI_CONFIG,
                sha256(config),
                config.length,
                OCI_LAYER,
                sha256(layer),
                layer.length);

    // A multi-platform push stores each platform's manifest by digest before the index.
    final var manifestDigest = sha256(manifest.getBytes(StandardCharsets.UTF_8));
    final var image = this.putManifest(repo, manifestDigest, OCI_MANIFEST, manifest);
    assertThat(image.getStatus()).isEqualTo(201);

    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}]}"
            .formatted(OCI_INDEX, OCI_MANIFEST, manifestDigest, manifest.length());

    final var multiPlatform = this.putManifest(repo, "multi", OCI_INDEX, index);

    assertThat(multiPlatform.getStatus()).isEqualTo(201);
    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE)).isPresent();
  }

  @Test
  @DisplayName(
      "an image config blob without os or architecture is refused, and nothing is left behind"
          + " (RPS-1116)")
  void imageConfigWithoutOsOrArchitectureIsRejected() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config = "{}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest = this.imageManifest(config, layer, OCI_CONFIG);

    final var response = this.putManifest(repo, "latest", OCI_MANIFEST, manifest);

    this.assertConfigRejected(response, repo);
  }

  @Test
  @DisplayName(
      "a config blob that is not JSON is refused when its media type is an image config"
          + " (RPS-1116)")
  void nonJsonImageConfigIsRejected() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config = "not json".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest = this.imageManifest(config, layer, OCI_CONFIG);

    final var response = this.putManifest(repo, "latest", OCI_MANIFEST, manifest);

    this.assertConfigRejected(response, repo);
  }

  @Test
  @DisplayName(
      "a non-image config media type is an OCI artifact: stored with platform \"unknown\" even"
          + " though its config has no os/architecture (RPS-1116)")
  void artifactConfigStoresUnknownPlatform() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config = "not json at all".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest =
        this.imageManifest(config, layer, "application/vnd.example.artifact.config.v1+json");

    final var response = this.putManifest(repo, "latest", OCI_MANIFEST, manifest);

    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    final var image = this.imageRepository.findByRepoIdAndName(repo.getId(), IMAGE).orElseThrow();
    final var manifestDigest = sha256(manifest.getBytes(StandardCharsets.UTF_8));
    final var stored =
        this.manifestRepository.findByRepoIdAndImageIdAndDigestList(
            repo.getId(), image.getId(), manifestDigest);
    assertThat(stored).isNotEmpty();
    assertThat(stored.getFirst().getPlatform()).isEqualTo(DockerConstants.UNKNOWN_PLATFORM);
  }

  @Test
  @DisplayName(
      "an index entry without a platform is accepted, not refused (RPS-1117): an index can group"
          + " an artifact and its referrers, not only per-platform images")
  void indexEntryWithoutPlatformIsAccepted() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest = this.imageManifest(config, layer, OCI_CONFIG);
    final var manifestDigest = sha256(manifest.getBytes(StandardCharsets.UTF_8));
    final var childPush = this.putManifest(repo, manifestDigest, OCI_MANIFEST, manifest);
    assertThat(childPush.getStatus()).as(childPush.getContentAsString()).isEqualTo(201);

    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
            .formatted(OCI_INDEX, OCI_MANIFEST, manifestDigest, manifest.length());

    final var indexPush = this.putManifest(repo, "multi", OCI_INDEX, index);

    assertThat(indexPush.getStatus()).as(indexPush.getContentAsString()).isEqualTo(201);
  }

  @ParameterizedTest(name = "schemaVersion {0}")
  @ValueSource(strings = {"4294967298", "-1", "3"})
  @DisplayName(
      "a schemaVersion that does not fit an int, or does not match the media type, is refused"
          + " instead of being truncated or stored as-is (RPS-1151)")
  void invalidSchemaVersionIsRejected(final String schemaVersion) throws Exception {
    final var repo = this.dockerRepo();
    final var body =
        "{\"schemaVersion\":"
            + schemaVersion
            + ",\"config\":{\"digest\":\""
            + DIGEST
            + "\"},\"layers\":[]}";

    final var response = this.putManifest(repo, "latest", OCI_MANIFEST, body);

    this.assertRejected(response, SCHEMA_VERSION_INVALID, repo);
  }

  @Test
  @DisplayName(
      "an unknown manifest Content-Type is refused with 400 MANIFEST_INVALID, not a 500"
          + " (RPS-1110)")
  void unknownManifestContentTypeIsRejected() throws Exception {
    final var repo = this.dockerRepo();

    final var response = this.putManifest(repo, "latest", "text/plain", "{}");

    this.assertRejected(response, MEDIA_TYPE_UNSUPPORTED, repo);
  }

  @Test
  @DisplayName(
      "a pull whose Accept header names no manifest media type this registry serves is refused"
          + " with 406 and an OCI errors[] body, not silently served the default type (RPS-1110)")
  void pullWithUnacceptableAcceptIsRefused() throws Exception {
    final var repo = this.dockerRepo();
    final var token = this.adminProtocolBearerToken();
    final var config =
        "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);
    final var layer = "layer-content".getBytes(StandardCharsets.UTF_8);
    this.pushBlob(repo, config, token);
    this.pushBlob(repo, layer, token);
    final var manifest = this.imageManifest(config, layer, OCI_CONFIG);
    final var push = this.putManifest(repo, "latest", OCI_MANIFEST, manifest);
    assertThat(push.getStatus()).as(push.getContentAsString()).isEqualTo(201);

    final var response = this.getManifestWithAccept(repo, "latest", "application/xml", token);

    assertThat(response.getStatus()).isEqualTo(406);
    assertThat(response.getContentAsString())
        .contains("\"errors\"")
        .contains("\"code\":\"UNSUPPORTED\"");
  }

  /**
   * Asserts an RPS-1116 config rejection: unlike {@link #assertRejected}, the image row is NOT
   * asserted absent -- {@code findOrCreateImage} runs, and commits within this test's own
   * transaction, before {@code saveManifest} ever reaches {@code extractPlatform}. What must be
   * absent is the manifest itself: the ordering fix moves platform extraction before the manifest
   * is written to disk, so a rejected config leaves no manifest file behind (the flip-and-fail
   * check for this ordering bug: revert it and this assertion catches the leaked file).
   */
  private void assertConfigRejected(final MockHttpServletResponse response, final Repo repo)
      throws Exception {
    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"code\":\"MANIFEST_INVALID\"")
        .contains("\"detail\":\"" + CONFIG_INVALID + "\"");
    assertThat(storageDirOf(repo).resolve("manifests"))
        .satisfiesAnyOf(
            path -> assertThat(path).doesNotExist(),
            path -> assertThat(path.toFile().list()).isEmpty());
  }

  private String imageManifest(final byte[] config, final byte[] layer, final String configType) {
    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
        .formatted(
            OCI_MANIFEST,
            configType,
            sha256(config),
            config.length,
            OCI_LAYER,
            sha256(layer),
            layer.length);
  }

  private MockHttpServletResponse getManifestWithAccept(
      final Repo repo, final String reference, final String accept, final String token)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), IMAGE, reference)
                .header("Accept", accept)
                .header(AUTHORIZATION, token)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private void pushBlob(final Repo repo, final byte[] blob, final String token) throws Exception {
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
                    .param("digest", sha256(blob))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, token)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).isEqualTo(201);
  }
}
