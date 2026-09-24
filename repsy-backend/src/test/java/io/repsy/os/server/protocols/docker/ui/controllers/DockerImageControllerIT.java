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
package io.repsy.os.server.protocols.docker.ui.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.PagingAssertions;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestList;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestListManifest;
import io.repsy.protocols.docker.shared.tag.dtos.Platform;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** Full-stack Testcontainers coverage for the Docker image-management API. */
@DisplayName("DockerImageController /api/docker/images/*")
class DockerImageControllerIT extends AbstractIntegrationTest {

  private static final String MANIFEST_MEDIA_TYPE =
      "application/vnd.docker.distribution.manifest.v2+json";
  private static final String CONFIG_MEDIA_TYPE = "application/vnd.docker.container.image.v1+json";
  private static final String LAYER_MEDIA_TYPE =
      "application/vnd.docker.image.rootfs.diff.tar.gzip";
  private static final String MANIFEST_LIST_MEDIA_TYPE =
      "application/vnd.docker.distribution.manifest.list.v2+json";

  @Autowired private ImageTxService imageService;
  @Autowired private LayerTxService layerService;
  @Autowired private ManifestTxService manifestService;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static Map<String, Object> data(final String body) {
    return JsonPath.read(body, "$.data");
  }

  private Repo dockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"));
  }

  private Repo privateDockerRepo() {
    return this.seedRepo(RepoType.DOCKER, uniqueRepoName("private"), true, null);
  }

  private ImageFixture seedImage(final Repo repo, final String imageName, final String tag)
      throws Exception {
    return this.seedImage(repo, imageName, tag, "sha256:" + "1".repeat(64));
  }

  private ImageFixture seedImage(
      final Repo repo, final String imageName, final String tag, final String configDigest)
      throws Exception {
    final String layerDigest = "sha256:" + "2".repeat(64);
    final String manifestDigest =
        "sha256:" + "%064x".formatted((long) tag.hashCode() & 0xffffffffL);
    final String configJson = "{\"architecture\":\"amd64\",\"os\":\"linux\"}";
    final String manifestJson =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"size\":%d,\"digest\":\"%s\"},\"layers\":[{\"mediaType\":\"%s\",\"size\":3,\"digest\":\"%s\"}]}"
            .formatted(
                MANIFEST_MEDIA_TYPE,
                CONFIG_MEDIA_TYPE,
                configJson.length(),
                configDigest,
                LAYER_MEDIA_TYPE,
                layerDigest);
    final var image = this.imageService.findOrCreateImage(repo.getId(), imageName);
    this.layerService.findOrCreate(
        LayerForm.builder()
            .imageName(imageName)
            .mediaType(CONFIG_MEDIA_TYPE)
            .digest(configDigest)
            .size(configJson.length())
            .build(),
        repo.getId());
    this.layerService.findOrCreate(
        LayerForm.builder()
            .imageName(imageName)
            .mediaType(LAYER_MEDIA_TYPE)
            .digest(layerDigest)
            .size(3)
            .build(),
        repo.getId());

    final var manifestInfo = this.objectMapper.readValue(manifestJson, ManifestInfo.class);
    final var form =
        ManifestForm.builder()
            .tagName(tag)
            .contentType(MANIFEST_MEDIA_TYPE)
            .manifestJson(manifestJson)
            .manifestBytes(manifestJson.getBytes(StandardCharsets.UTF_8))
            .digest(manifestDigest)
            .build();
    this.manifestService.createSinglePlatformManifest(
        repo.getId(), image, TagForm.of(form, imageName, "linux/amd64", manifestInfo));

    final var storage = storageDirOf(repo);
    Files.createDirectories(storage.resolve("blobs"));
    Files.createDirectories(storage.resolve("manifests"));
    Files.writeString(storage.resolve("blobs").resolve(configDigest), configJson);
    Files.writeString(storage.resolve("blobs").resolve(layerDigest), "abc");
    Files.writeString(storage.resolve("manifests").resolve(manifestDigest), manifestJson);
    this.entityManager.flush();
    this.entityManager.clear();
    return new ImageFixture(imageName, tag, configDigest, manifestDigest, manifestJson);
  }

  /**
   * Seeds a multi-platform tag the way the protocol path stores it: the manifest list under the tag
   * name and each per-platform manifest under its own digest, with a {@code Manifest} row per child
   * but no {@code Tag} row.
   */
  private MultiPlatformFixture seedMultiPlatformImage(
      final Repo repo, final String imageName, final String tag) throws Exception {
    final String layerDigest = "sha256:" + "2".repeat(64);
    final String listDigest = "sha256:" + "c".repeat(64);
    final var image = this.imageService.findOrCreateImage(repo.getId(), imageName);
    this.layerService.findOrCreate(
        LayerForm.builder()
            .imageName(imageName)
            .mediaType(LAYER_MEDIA_TYPE)
            .digest(layerDigest)
            .size(3)
            .build(),
        repo.getId());

    final var storage = storageDirOf(repo);
    Files.createDirectories(storage.resolve("blobs"));
    Files.createDirectories(storage.resolve("manifests"));
    Files.writeString(storage.resolve("blobs").resolve(layerDigest), "abc");

    final var listEntries = new java.util.ArrayList<ManifestListManifest>();
    final var childDigests = new java.util.ArrayList<String>();
    final var childJsons = new java.util.ArrayList<String>();
    for (final var arch : List.of("amd64", "arm64")) {
      final String configDigest = "sha256:" + (arch.equals("amd64") ? "3" : "4").repeat(64);
      final String childDigest = "sha256:" + (arch.equals("amd64") ? "a" : "b").repeat(64);
      final String configJson = "{\"architecture\":\"%s\",\"os\":\"linux\"}".formatted(arch);
      final String childJson =
          "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"size\":%d,\"digest\":\"%s\"},\"layers\":[{\"mediaType\":\"%s\",\"size\":3,\"digest\":\"%s\"}]}"
              .formatted(
                  MANIFEST_MEDIA_TYPE,
                  CONFIG_MEDIA_TYPE,
                  configJson.length(),
                  configDigest,
                  LAYER_MEDIA_TYPE,
                  layerDigest);
      this.layerService.findOrCreate(
          LayerForm.builder()
              .imageName(imageName)
              .mediaType(CONFIG_MEDIA_TYPE)
              .digest(configDigest)
              .size(configJson.length())
              .build(),
          repo.getId());
      Files.writeString(storage.resolve("blobs").resolve(configDigest), configJson);
      Files.writeString(storage.resolve("manifests").resolve(childDigest), childJson);

      // A child is pushed by its digest before the index that references it: a manifest row
      // without a tag.
      final var childForm =
          ManifestForm.builder()
              .tagName(childDigest)
              .contentType(MANIFEST_MEDIA_TYPE)
              .manifestJson(childJson)
              .manifestBytes(childJson.getBytes(StandardCharsets.UTF_8))
              .digest(childDigest)
              .build();
      this.manifestService.createSinglePlatformManifest(
          repo.getId(),
          image,
          TagForm.of(
              childForm,
              imageName,
              "linux/" + arch,
              this.objectMapper.readValue(childJson, ManifestInfo.class)));

      childDigests.add(childDigest);
      childJsons.add(childJson);
      listEntries.add(
          new ManifestListManifest(
              null, childDigest, MANIFEST_MEDIA_TYPE, new Platform(arch, "linux", null), 1));
    }

    final var manifestList = new ManifestList();
    manifestList.setSchemaVersion(2);
    manifestList.setMediaType(MANIFEST_LIST_MEDIA_TYPE);
    manifestList.setManifests(listEntries);
    final String listJson = this.objectMapper.writeValueAsString(manifestList);
    Files.writeString(storage.resolve("manifests").resolve(listDigest), listJson);

    final var form =
        ManifestForm.builder()
            .tagName(tag)
            .contentType(MANIFEST_LIST_MEDIA_TYPE)
            .manifestJson(listJson)
            .manifestBytes(listJson.getBytes(StandardCharsets.UTF_8))
            .digest(listDigest)
            .build();
    this.manifestService.createManifestList(
        repo.getId(), image.getId(), TagForm.of(form, imageName, "Multiplatform", manifestList));
    this.entityManager.flush();
    this.entityManager.clear();
    return new MultiPlatformFixture(imageName, tag, listDigest, listJson, childDigests, childJsons);
  }

  private record MultiPlatformFixture(
      String imageName,
      String tag,
      String listDigest,
      String listJson,
      List<String> childDigests,
      List<String> childJsons) {}

  private record ImageFixture(
      String imageName,
      String tag,
      String configDigest,
      String manifestDigest,
      String manifestJson) {}

  @Nested
  @DisplayName("read endpoints")
  class Reads {

    @Test
    @DisplayName("lists images with the complete paging envelope")
    void listsImages() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "library/app", "latest");

      final var body =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
              "imagesFetched",
              "Packages are fetched.");
      final var page = data(body);
      assertThat(page).containsKeys("content", "page");
      assertThat((java.util.List<?>) page.get("content")).hasSize(1);
      assertThat((Map<String, Object>) ((java.util.List<?>) page.get("content")).getFirst())
          .containsKeys("name", "size", "updatedAt");
    }

    @Test
    @DisplayName("returns tag detail, manifest and config JSON")
    void readsTagManifestAndConfig() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();

      final var detail =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/tags/%s"
                          .formatted(repo.getName(), image.imageName, image.tag))
                      .header(AUTHORIZATION, token)),
              "tagDetailFetched",
              "Tag detail fetched");
      assertThat(data(detail))
          .containsKeys(
              "id",
              "digest",
              "configDigest",
              "imageName",
              "name",
              "platform",
              "mediaType",
              "createdAt");

      final var manifest =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/manifests/%s"
                          .formatted(repo.getName(), image.imageName, image.manifestDigest))
                      .header(AUTHORIZATION, token)),
              "manifestFetched",
              "Manifest fetched.");
      assertThat(JsonPath.<String>read(manifest, "$.data")).isEqualTo(image.manifestJson);

      final var config =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/configs/%s"
                          .formatted(repo.getName(), image.imageName, image.configDigest))
                      .header(AUTHORIZATION, token)),
              "configFetched",
              "Config fetched.");
      assertThat(JsonPath.<String>read(config, "$.data")).contains("architecture");
    }

    @Test
    @DisplayName("returns the manifest when the reference is a tag name")
    void readsManifestByTagReference() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedImage(repo, "app", "latest");

      final var manifest =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/manifests/%s"
                          .formatted(repo.getName(), image.imageName, image.tag))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
              "manifestFetched",
              "Manifest fetched.");
      assertThat(JsonPath.<String>read(manifest, "$.data")).isEqualTo(image.manifestJson);
    }

    @Test
    @DisplayName("returns a per-platform manifest of a multi-platform tag by its digest (RPS-946)")
    void readsPerPlatformManifestByDigest() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedMultiPlatformImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();

      for (int i = 0; i < image.childDigests.size(); i++) {
        final var manifest =
            DockerImageControllerIT.this.expectSuccess(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/manifests/%s"
                            .formatted(repo.getName(), image.imageName, image.childDigests.get(i)))
                        .header(AUTHORIZATION, token)),
                "manifestFetched",
                "Manifest fetched.");
        assertThat(JsonPath.<String>read(manifest, "$.data")).isEqualTo(image.childJsons.get(i));
      }
    }

    @Test
    @DisplayName("returns the manifest list by the tag name and by its own digest (RPS-946)")
    void readsManifestListByTagAndDigest() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedMultiPlatformImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();

      for (final var reference : List.of(image.tag, image.listDigest)) {
        final var manifest =
            DockerImageControllerIT.this.expectSuccess(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/manifests/%s"
                            .formatted(repo.getName(), image.imageName, reference))
                        .header(AUTHORIZATION, token)),
                "manifestFetched",
                "Manifest fetched.");
        assertThat(JsonPath.<String>read(manifest, "$.data")).isEqualTo(image.listJson);
      }
    }

    @Test
    @DisplayName("does not resolve a per-platform digest through another image (RPS-946)")
    void doesNotResolveDigestOfAnotherImage() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedMultiPlatformImage(repo, "app", "latest");
      DockerImageControllerIT.this.seedImage(repo, "other", "latest");

      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/other/manifests/%s"
                      .formatted(repo.getName(), image.childDigests.getFirst()))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
          HttpStatus.NOT_FOUND,
          "tagNotFound",
          "tagNotFound",
          "Tag not found.");
    }

    @Test
    @DisplayName("lists tags and manifests with filters, paging metadata and DTO fields")
    void listsTagsAndManifests() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.seedImage(repo, "app", "stable");
      final var token = DockerImageControllerIT.this.userBearerToken();

      final var tags =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app/tags".formatted(repo.getName()))
                      .param("name", "latest")
                      .param("page", "0")
                      .param("size", "1")
                      .header(AUTHORIZATION, token)),
              "imageTagsFetched",
              "Image tags fetched.");
      final var tagPage = data(tags);
      assertThat(tagPage).containsKeys("content", "page");
      assertThat((java.util.List<?>) tagPage.get("content")).hasSize(1);
      assertThat((Map<String, Object>) ((java.util.List<?>) tagPage.get("content")).getFirst())
          .containsKeys("name", "platform", "lastUpdatedAt")
          .containsEntry("name", "latest");
      assertThat((Map<String, Object>) tagPage.get("page"))
          .containsEntry("size", 1)
          .containsEntry("totalElements", 1);

      final var manifests =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app/tags/latest/manifests".formatted(repo.getName()))
                      .param("page", "0")
                      .param("size", "10")
                      .header(AUTHORIZATION, token)),
              "tagLayersFetched",
              "Tag layers fetched.");
      final var manifestPage = data(manifests);
      assertThat((java.util.List<?>) manifestPage.get("content")).hasSize(1);
      assertThat((Map<String, Object>) ((java.util.List<?>) manifestPage.get("content")).getFirst())
          .containsKeys("name", "digest", "createdAt", "platform", "configDigest");
    }

    @Test
    @DisplayName("resolves the default tag through the image route")
    void resolvesDefaultTag() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");

      final var body =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
              "tagDetailFetched",
              "Tag detail fetched");
      assertThat(data(body)).containsEntry("name", "latest").containsEntry("imageName", "app");
    }

    @Test
    @DisplayName("returns 404 for unknown image and malformed references")
    void unknownItems() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var token = DockerImageControllerIT.this.userBearerToken();
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/missing/tags/latest".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/manifests/sha256:%s"
                      .formatted(repo.getName(), "f".repeat(64)))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "tagNotFound",
          "tagNotFound",
          "Tag not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/manifests/missing-tag".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "tagNotFound",
          "tagNotFound",
          "Tag not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/missing/configs/sha256:%s"
                      .formatted(repo.getName(), "f".repeat(64)))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
    }

    @Test
    @DisplayName("returns layerNotFound for a config digest the image does not reference")
    void configDigestUnknownToImage() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/%s/configs/sha256:%s"
                      .formatted(repo.getName(), image.imageName, "f".repeat(64)))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
          HttpStatus.NOT_FOUND,
          "layerNotFound",
          "layerNotFound",
          "Layer not found.");
    }

    @Test
    @DisplayName("scopes the config endpoint to the requested image within a repository")
    void configIsScopedToImage() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var app = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var other =
          DockerImageControllerIT.this.seedImage(
              repo, "other", "latest", "sha256:" + "3".repeat(64));
      final var token = DockerImageControllerIT.this.userBearerToken();

      for (final var image : List.of(app, other)) {
        final var config =
            DockerImageControllerIT.this.expectSuccess(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/configs/%s"
                            .formatted(repo.getName(), image.imageName, image.configDigest))
                        .header(AUTHORIZATION, token)),
                "configFetched",
                "Config fetched.");
        assertThat(JsonPath.<String>read(config, "$.data")).contains("architecture");
      }

      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/%s/configs/%s"
                      .formatted(repo.getName(), app.imageName, other.configDigest))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "layerNotFound",
          "layerNotFound",
          "Layer not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/%s/configs/%s"
                      .formatted(repo.getName(), other.imageName, app.configDigest))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "layerNotFound",
          "layerNotFound",
          "Layer not found.");
    }

    @Test
    @DisplayName("answers an unknown Basic username exactly like a wrong password (RPS-906)")
    void basicCredentialsDoNotRevealUsernames() throws Exception {
      final var repo = DockerImageControllerIT.this.privateDockerRepo();
      final var username = uniqueUsername("basic");
      DockerImageControllerIT.this.createUser(username, UserRole.USER);

      for (final var auth :
          List.of(basicAuth(username, "wrong"), basicAuth(uniqueUsername("ghost"), "wrong"))) {
        DockerImageControllerIT.this.expectError(
            DockerImageControllerIT.this.perform(
                get("/api/docker/images/%s".formatted(repo.getName())).header(AUTHORIZATION, auth)),
            HttpStatus.UNAUTHORIZED,
            "unAuthorized",
            "unAuthorized",
            "The user has logged in but has no permissions.");
      }
    }

    @Test
    @DisplayName("rejects expired and malformed authorization tokens")
    void rejectsInvalidTokens() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s".formatted(repo.getName()))
                  .header(AUTHORIZATION, "Bearer not-a-jwt")),
          HttpStatus.UNAUTHORIZED,
          "accessNotAllowed",
          "accessNotAllowed",
          "Access isn't allowed.");
      final var user =
          DockerImageControllerIT.this.createUser("expired" + randomTag(), UserRole.USER);
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s".formatted(repo.getName()))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.expiredBearerTokenFor(user))),
          HttpStatus.UNAUTHORIZED,
          "sessionExpired",
          "sessionExpired",
          "Session expired.");
    }
  }

  @Nested
  @DisplayName("authorization and mutation endpoints")
  class Mutations {

    @Test
    @DisplayName("denies anonymous access and read-only deletion")
    void protectsManageOperations() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app".formatted(repo.getName()))),
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          "The user has logged in but has no permissions.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app".formatted(repo.getName()))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");
    }

    @Test
    @DisplayName("deletes a tag while keeping the image and returns a full success envelope")
    void deletesTag() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var fixture = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var body =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  delete("/api/docker/images/%s/app/tags/latest".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())),
              "tagDeleted",
              "Tag deleted.");
      assertThat((Object) JsonPath.read(body, "$.data")).isNull();
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app/tags/latest".formatted(repo.getName()))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())),
          HttpStatus.NOT_FOUND,
          "tagNotFound",
          "tagNotFound",
          "Tag not found.");
      // A tag is only a pointer: its manifest stays stored, with its file, and pullable by digest
      // (RPS-1216).
      final var image =
          DockerImageControllerIT.this.imageService.findImageInfoByRepoIdAndName(
              repo.getId(), fixture.imageName);
      assertThat(DockerImageControllerIT.this.manifestRepository.findAllByImageId(image.getId()))
          .extracting(manifest -> manifest.getDigest())
          .containsExactly(fixture.manifestDigest);
      assertThat(storageDirOf(repo).resolve("manifests").resolve(fixture.manifestDigest)).exists();
      DockerImageControllerIT.this.expectSuccess(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/manifests/%s"
                      .formatted(repo.getName(), fixture.manifestDigest))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())),
          "manifestFetched",
          "Manifest fetched.");
    }

    @Test
    @DisplayName("deletes an image and the orphan layer route is not shadowed")
    void deletesImageAndOrphans() throws Exception {
      final var repo = DockerImageControllerIT.this.privateDockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.adminBearerToken();
      DockerImageControllerIT.this.expectSuccess(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/blobs/%s/orphan-layers".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          "orphanLayersDeleted",
          "Orphan layers deleted.");
      DockerImageControllerIT.this.expectSuccess(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          "imageDeleted",
          "Image deleted.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/tags/latest".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
    }

    @Test
    @DisplayName("returns forbidden for an authenticated user without manage permission")
    void deniesManageOperationsForReadOnlyUser() throws Exception {
      final var repo = DockerImageControllerIT.this.privateDockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/blobs/%s/orphan-layers".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");
    }
  }

  @Nested
  @DisplayName("paging and sorting of the list endpoints")
  class PagingAndSorting {

    private static final String IMAGES = "/api/docker/images/%s";
    private static final String TAGS = "/api/docker/images/%s/app/tags";
    private static final String MANIFESTS = "/api/docker/images/%s/app/tags/latest/manifests";

    static Stream<String> endpoints() {
      return Stream.of(IMAGES, TAGS, MANIFESTS);
    }

    static Stream<Arguments> acceptedSorts() {
      return Stream.of(
              Arguments.of(IMAGES, List.of("id", "name", "updatedAt", "lastUpdatedAt")),
              Arguments.of(TAGS, List.of("id", "name", "createdAt")),
              Arguments.of(MANIFESTS, List.of("id", "name", "createdAt")))
          .flatMap(
              args ->
                  ((List<?>) args.get()[1])
                      .stream().map(property -> Arguments.of(args.get()[0], property)));
    }

    static Stream<Arguments> invalidPagingOnEveryEndpoint() {
      return endpoints()
          .flatMap(
              path ->
                  PagingAssertions.invalidPagingParams()
                      .map(args -> Arguments.of(path, args.get()[0], args.get()[1])));
    }

    private Repo seededRepo() throws Exception {
      final var it = DockerImageControllerIT.this;
      final var repo = it.dockerRepo();

      it.seedImage(repo, "app", "latest");
      it.seedImage(repo, "app", "stable");
      it.seedImage(repo, "other", "latest");

      return repo;
    }

    private ResultActions list(
        final Repo repo, final String path, final String param, final String value)
        throws Exception {
      final var it = DockerImageControllerIT.this;

      return it.perform(
          get(path.formatted(repo.getName()))
              .param(param, value)
              .header(AUTHORIZATION, it.userBearerToken()));
    }

    @ParameterizedTest(name = "{0} sort={1}")
    @MethodSource("acceptedSorts")
    @DisplayName("accepts every documented sort property in both directions")
    void acceptsSort(final String path, final String property) throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, path, "sort", property + ",asc").andExpect(status().isOk());
      this.list(repo, path, "sort", property + ",desc").andExpect(status().isOk());
    }

    @Test
    @DisplayName("orders the images and tags by the requested sort property")
    void ordersByRequestedProperty() throws Exception {
      final var repo = this.seededRepo();

      this.list(repo, IMAGES, "sort", "name,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("app"));
      this.list(repo, IMAGES, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("other"));
      this.list(repo, TAGS, "sort", "name,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("latest"));
      this.list(repo, TAGS, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content[0].name").value("stable"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    @DisplayName("returns 400 validationError naming sort for an unknown sort property")
    void unknownSortIs400(final String path) throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seededRepo(), path, "sort", PagingAssertions.UNKNOWN_SORT), "sort");
    }

    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagingOnEveryEndpoint")
    @DisplayName("returns 400 validationError naming the parameter for a bad page or size")
    void invalidPagingParam(final String path, final String param, final String value)
        throws Exception {
      PagingAssertions.expectInvalidParameter(
          this.list(this.seededRepo(), path, param, value), param);
    }
  }
}
