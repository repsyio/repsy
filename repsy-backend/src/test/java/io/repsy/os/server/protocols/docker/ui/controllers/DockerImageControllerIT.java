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

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.docker.shared.layer.dtos.LayerForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestForm;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** Full-stack Testcontainers coverage for the Docker image-management API. */
@DisplayName("DockerImageController /api/docker/images/*")
class DockerImageControllerIT extends AbstractIntegrationTest {

  private static final String MANIFEST_MEDIA_TYPE =
      "application/vnd.docker.distribution.manifest.v2+json";
  private static final String CONFIG_MEDIA_TYPE = "application/vnd.docker.container.image.v1+json";
  private static final String LAYER_MEDIA_TYPE =
      "application/vnd.docker.image.rootfs.diff.tar.gzip";

  @Autowired private ImageTxService imageService;
  @Autowired private LayerTxService layerService;
  @Autowired private ManifestTxService manifestService;
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
    final String configDigest = "sha256:" + "1".repeat(64);
    final String layerDigest = "sha256:" + "2".repeat(64);
    final String manifestDigest = "sha256:" + "3".repeat(64);
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
    Files.writeString(
        storage
            .resolve("manifests")
            .resolve(ManifestNameGenerator.generate(repo.getId(), imageName, tag)),
        manifestJson);
    this.entityManager.flush();
    this.entityManager.clear();
    return new ImageFixture(imageName, tag, configDigest, manifestDigest, manifestJson);
  }

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
          HttpStatus.UNAUTHORIZED,
          "unAuthorized",
          "unAuthorized",
          "The user has logged in but has no permissions.");
    }

    @Test
    @DisplayName("deletes a tag while keeping the image and returns a full success envelope")
    void deletesTag() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var body =
          DockerImageControllerIT.this.expectSuccess(
              DockerImageControllerIT.this.perform(
                  delete("/api/docker/images/%s/app/tags/latest".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())),
              "tagDeleted",
              "Tag deleted.");
      assertThat((Object) JsonPath.read(body, "$.data")).isNull();
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
  }
}
