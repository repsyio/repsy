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
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.services.ManifestTxService;
import io.repsy.os.server.protocols.docker.shared.tag.services.UntaggedManifestFinder;
import io.repsy.os.server.shared.http.BareBodyAssertions;
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
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

  /**
   * What {@code seedImage} stores as the config blob of every manifest (the architecture aside).
   */
  private static final String CONFIG_JSON = "{\"architecture\":\"amd64\",\"os\":\"linux\"}";

  private static final int LAYER_SIZE = 3;
  private static final String MANIFEST_LIST_MEDIA_TYPE =
      "application/vnd.docker.distribution.manifest.list.v2+json";

  // Named differently from the base class' method, or it would hide that one.
  @DynamicPropertySource
  static void registerStatisticsProperty(final DynamicPropertyRegistry registry) {
    // Lets the query-count test read the number of statements a request ran.
    registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
  }

  @Autowired private ImageTxService imageService;
  @Autowired private LayerTxService layerService;
  @Autowired private ManifestTxService manifestService;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private static Map<String, Object> data(final String body) {
    return JsonPath.read(body, "$");
  }

  /** A manifest or config body: the stored text as a JSON string literal. */
  private String text(final String body) {
    return this.objectMapper.readValue(body, String.class);
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
    final var image = this.imageService.getOrCreateImage(repo.getId(), imageName);
    this.layerService.getOrCreate(
        LayerForm.builder()
            .imageName(imageName)
            .mediaType(CONFIG_MEDIA_TYPE)
            .digest(configDigest)
            .size(configJson.length())
            .build(),
        repo.getId());
    this.layerService.getOrCreate(
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
    final var image = this.imageService.getOrCreateImage(repo.getId(), imageName);
    this.layerService.getOrCreate(
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
      this.layerService.getOrCreate(
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
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())));
      final var page = data(body);
      assertThat(page).containsKeys("content", "page");
      assertThat((java.util.List<?>) page.get("content")).hasSize(1);
      assertThat((Map<String, Object>) ((java.util.List<?>) page.get("content")).getFirst())
          .containsKeys(
              "name", "size", "updatedAt", "tagCount", "untaggedManifestCount", "untaggedSize");
    }

    @Test
    @DisplayName("_ and % in q match literally on the image list and the tag list (RPS-1891)")
    void likeWildcardsInQMatchLiterally() throws Exception {
      final var it = DockerImageControllerIT.this;
      final var repo = it.dockerRepo();
      for (final var image : List.of("a_b", "axb")) {
        for (final var tag : List.of("v_1", "vx1")) {
          it.seedImage(repo, image, tag);
        }
      }

      final var images = "/api/docker/images/%s".formatted(repo.getName());
      final var auth = it.adminBearerToken();
      final var literalImage =
          it.perform(get(images).param("q", "a_b").header(AUTHORIZATION, auth));
      literalImage
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].name").value("a_b"));
      it.perform(get(images).param("q", "%").header(AUTHORIZATION, auth))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(0));

      it.perform(get(images + "/a_b/tags").param("q", "v_1").header(AUTHORIZATION, auth))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(1))
          .andExpect(jsonPath("$.content[0].name").value("v_1"));
      it.perform(get(images + "/a_b/tags").param("q", "%").header(AUTHORIZATION, auth))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    private Map<String, Object> listedImage(final Repo repo, final String imageName)
        throws Exception {
      final var body =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
      final List<Map<String, Object>> content = JsonPath.read(body, "$.content");

      return content.stream()
          .filter(image -> imageName.equals(image.get("name")))
          .findFirst()
          .orElseThrow();
    }

    private void deleteTag(final Repo repo, final String imageName, final String tag)
        throws Exception {
      BareBodyAssertions.expectNoContent(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/%s/tags/%s".formatted(repo.getName(), imageName, tag))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
    }

    @Test
    @DisplayName("an image whose last tag was deleted is still listed, with what it stores")
    void anImageWithoutTagsIsListedWithItsUntaggedManifests() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var config = "sha256:" + "5".repeat(64);
      DockerImageControllerIT.this.seedImage(repo, "app", "latest", config);

      final var tagged = this.listedImage(repo, "app");
      assertThat(tagged)
          .containsEntry("tagCount", 1)
          .containsEntry("untaggedManifestCount", 0)
          .containsEntry("untaggedSize", 0);

      this.deleteTag(repo, "app", "latest");

      final var emptied = this.listedImage(repo, "app");
      assertThat(emptied)
          .containsEntry("tagCount", 0)
          .containsEntry("untaggedManifestCount", 1)
          .containsEntry("size", 0)
          .doesNotContainKey("digest");
      assertThat(((Number) emptied.get("untaggedSize")).longValue())
          .as("the config blob and the layer of the manifest it keeps")
          .isEqualTo(CONFIG_JSON.length() + LAYER_SIZE);
      assertThat(emptied.get("updatedAt"))
          .as("no tag to date it by: the last change of the image")
          .isNotNull();
    }

    @Test
    @DisplayName("an untagged manifest counts only the layers no tagged manifest of the image uses")
    void untaggedSizeExcludesLayersATaggedManifestUses() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var otherConfig = "sha256:" + "6".repeat(64);
      DockerImageControllerIT.this.seedImage(repo, "app", "kept");
      DockerImageControllerIT.this.seedImage(repo, "app", "dropped", otherConfig);

      this.deleteTag(repo, "app", "dropped");

      final var image = this.listedImage(repo, "app");
      assertThat(image).containsEntry("tagCount", 1).containsEntry("untaggedManifestCount", 1);
      // Both manifests use the same layer; only the config blob of the dropped one is its own.
      assertThat(((Number) image.get("untaggedSize")).longValue()).isEqualTo(CONFIG_JSON.length());
    }

    @Test
    @DisplayName("the manifests an index lists are not untagged while a tag points at the index")
    void manifestsOfATaggedIndexAreNotUntagged() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var fixture = DockerImageControllerIT.this.seedMultiPlatformImage(repo, "multi", "v1");

      final var tagged = this.listedImage(repo, "multi");
      assertThat(tagged)
          .containsEntry("tagCount", 1)
          .containsEntry("untaggedManifestCount", 0)
          .containsEntry("untaggedSize", 0);

      this.deleteTag(repo, "multi", fixture.tag());

      final var emptied = this.listedImage(repo, "multi");
      assertThat(emptied)
          .containsEntry("tagCount", 0)
          .containsEntry("untaggedManifestCount", 3)
          .containsEntry("size", 0);
      assertThat(((Number) emptied.get("untaggedSize")).longValue())
          .as("the shared layer once, and the two config blobs")
          .isEqualTo(LAYER_SIZE + 2L * CONFIG_JSON.length());
    }

    @Test
    @DisplayName("the image detail is its list row, and 404 imageNotFound when it is gone")
    void summarizesOneImage() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.seedImage(repo, "app-2", "latest");
      final var token = DockerImageControllerIT.this.adminBearerToken();
      this.deleteTag(repo, "app", "latest");

      final var body =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app".formatted(repo.getName()))
                      .header(AUTHORIZATION, token)));

      assertThat(data(body))
          .containsEntry("name", "app")
          .containsEntry("tagCount", 0)
          .containsEntry("untaggedManifestCount", 1);
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/ghost".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
    }

    @Test
    @DisplayName("returns tag detail, manifest and config JSON")
    void readsTagManifestAndConfig() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();

      final var detail =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/tags/%s"
                          .formatted(repo.getName(), image.imageName, image.tag))
                      .header(AUTHORIZATION, token)));
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
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/manifests/%s"
                          .formatted(repo.getName(), image.imageName, image.manifestDigest))
                      .header(AUTHORIZATION, token)));
      assertThat(text(manifest)).isEqualTo(image.manifestJson);

      final var config =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/configs/%s"
                          .formatted(repo.getName(), image.imageName, image.configDigest))
                      .header(AUTHORIZATION, token)));
      assertThat(text(config)).contains("architecture");
    }

    @Test
    @DisplayName("returns the manifest when the reference is a tag name")
    void readsManifestByTagReference() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedImage(repo, "app", "latest");

      final var manifest =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/%s/manifests/%s"
                          .formatted(repo.getName(), image.imageName, image.tag))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())));
      assertThat(text(manifest)).isEqualTo(image.manifestJson);
    }

    @Test
    @DisplayName("returns a per-platform manifest of a multi-platform tag by its digest (RPS-946)")
    void readsPerPlatformManifestByDigest() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var image = DockerImageControllerIT.this.seedMultiPlatformImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.userBearerToken();

      for (int i = 0; i < image.childDigests.size(); i++) {
        final var manifest =
            BareBodyAssertions.expectBare(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/manifests/%s"
                            .formatted(repo.getName(), image.imageName, image.childDigests.get(i)))
                        .header(AUTHORIZATION, token)));
        assertThat(text(manifest)).isEqualTo(image.childJsons.get(i));
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
            BareBodyAssertions.expectBare(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/manifests/%s"
                            .formatted(repo.getName(), image.imageName, reference))
                        .header(AUTHORIZATION, token)));
        assertThat(text(manifest)).isEqualTo(image.listJson);
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
          "manifestNotFound",
          "manifestNotFound",
          "Manifest not found.");
    }

    @Test
    @DisplayName("lists tags and manifests with filters, paging metadata and DTO fields")
    void listsTagsAndManifests() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.seedImage(repo, "app", "stable");
      final var token = DockerImageControllerIT.this.userBearerToken();

      final var tags =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app/tags".formatted(repo.getName()))
                      .param("q", "latest")
                      .param("page", "0")
                      .param("size", "1")
                      .header(AUTHORIZATION, token)));
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
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app/tags/latest/manifests".formatted(repo.getName()))
                      .param("page", "0")
                      .param("size", "10")
                      .header(AUTHORIZATION, token)));
      final var manifestPage = data(manifests);
      assertThat((java.util.List<?>) manifestPage.get("content")).hasSize(1);
      assertThat((Map<String, Object>) ((java.util.List<?>) manifestPage.get("content")).getFirst())
          .containsKeys("name", "digest", "createdAt", "platform", "configDigest");
    }

    @Test
    @DisplayName("the image route returns the image row, not a tag")
    void imageRouteReturnsTheImageRow() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");

      final var body =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.userBearerToken())));
      assertThat(data(body)).containsEntry("name", "app").containsEntry("tagCount", 1);
    }

    @Test
    @DisplayName("returns 404 for unknown image and malformed references")
    void unknownItems() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var token = DockerImageControllerIT.this.userBearerToken();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
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
          "manifestNotFound",
          "manifestNotFound",
          "Manifest not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/manifests/missing-tag".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "tagNotFound",
          "tagNotFound",
          "Tag not found.");
      // RPS-1579: the tags and the manifests of an image the repo does not have are a 404 of the
      // image,
      // not an empty page or a missing tag.
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/missing/tags".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
      DockerImageControllerIT.this.expectError(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/missing/manifests/latest".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.NOT_FOUND,
          "imageNotFound",
          "imageNotFound",
          "Image not found.");
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
            BareBodyAssertions.expectBare(
                DockerImageControllerIT.this.perform(
                    get("/api/docker/images/%s/%s/configs/%s"
                            .formatted(repo.getName(), image.imageName, image.configDigest))
                        .header(AUTHORIZATION, token)));
        assertThat(text(config)).contains("architecture");
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
            "loginRequired",
            "unAuthorized",
            "Please log in: the credentials are missing or invalid, or the account is gone.");
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
  @DisplayName("untagged stats of a page of images (RPS-1566)")
  class UntaggedStatsOfAPage {

    /**
     * The per-image query the list used before RPS-1566, kept as the oracle: one recursive walk for
     * one image.
     */
    private static final String PER_IMAGE_UNTAGGED_SQL =
        """
        with recursive reach(manifest_id) as (
          select t."manifest_id" from "public"."docker_tag" t where t."image_id" = :imageId
          union
          select c."child_id" from "public"."docker_manifest_child" c
            join reach r on c."parent_id" = r.manifest_id
        )
        select
          (
            select count(*) from "public"."docker_manifest" m
            where m."image_id" = :imageId
              and m."id" not in (select manifest_id from reach)
          ),
          cast(coalesce((
            select sum(l."size") from "public"."docker_layer" l
            where l."id" in (
                select ml."layer_id" from "public"."docker_manifest_layer" ml
                  join "public"."docker_manifest" um on um."id" = ml."manifest_id"
                where um."image_id" = :imageId
                  and um."id" not in (select manifest_id from reach)
              )
              and l."id" not in (
                select rl."layer_id" from "public"."docker_manifest_layer" rl
                where rl."manifest_id" in (select manifest_id from reach)
              )
          ), 0) as bigint)
        """;

    @Autowired private UntaggedManifestFinder untaggedManifestFinder;
    @Autowired private ImageRepository imageRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private void deleteTag(final Repo repo, final String imageName, final String tag)
        throws Exception {
      BareBodyAssertions.expectNoContent(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/%s/tags/%s".formatted(repo.getName(), imageName, tag))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
    }

    /** The whole first page of the repo's images, by image name. */
    private Map<String, Map<String, Object>> listedImages(final Repo repo) throws Exception {
      final var body =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s".formatted(repo.getName()))
                      .param("size", "100")
                      .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
      final List<Map<String, Object>> content = JsonPath.read(body, "$.content");

      return content.stream()
          .collect(Collectors.toMap(image -> (String) image.get("name"), image -> image));
    }

    /** Runs the list request and returns how many JDBC statements Hibernate prepared for it. */
    private long statementsToList(final Repo repo) throws Exception {
      final var statistics = this.statistics();
      DockerImageControllerIT.this.entityManager.flush();
      DockerImageControllerIT.this.entityManager.clear();
      statistics.clear();
      this.listedImages(repo);
      return statistics.getPrepareStatementCount();
    }

    private Statistics statistics() {
      return this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** What the pre-RPS-1566 per-image query answers: {manifest count, size}. */
    private long[] perImageUntagged(final UUID imageId) {
      final var row =
          (Object[])
              DockerImageControllerIT.this
                  .entityManager
                  .createNativeQuery(PER_IMAGE_UNTAGGED_SQL)
                  .setParameter("imageId", imageId)
                  .getSingleResult();
      return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    @Test
    @DisplayName("a page of many images is listed in as many queries as a page of few")
    void constantQueryCount() throws Exception {
      final var fewImages = DockerImageControllerIT.this.dockerRepo();
      for (int i = 0; i < 3; i++) {
        DockerImageControllerIT.this.seedImage(fewImages, "few-" + i, "latest");
      }
      final var fewStatements = this.statementsToList(fewImages);

      final var manyImages = DockerImageControllerIT.this.dockerRepo();
      for (int i = 0; i < 25; i++) {
        DockerImageControllerIT.this.seedImage(manyImages, "many-" + i, "latest");
      }
      final var manyStatements = this.statementsToList(manyImages);

      assertThat(this.listedImages(manyImages)).hasSize(25);
      assertThat(manyStatements)
          .as("statements for 25 images, against %d for 3", fewStatements)
          .isEqualTo(fewStatements);
    }

    @Test
    @DisplayName("counts and sizes of a mixed page are those of the per-image query")
    void countsAndSizesMatchThePerImageQuery() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var it = DockerImageControllerIT.this;
      // Tagged only, with nothing untagged.
      it.seedImage(repo, "tagged", "latest");
      // Untagged only: the tag was deleted, the manifest stays.
      it.seedImage(repo, "untagged-only", "latest", "sha256:" + "5".repeat(64));
      this.deleteTag(repo, "untagged-only", "latest");
      // Shares its layer with a tagged manifest: only the config blob of the dropped one counts.
      it.seedImage(repo, "shared-layers", "kept");
      it.seedImage(repo, "shared-layers", "dropped", "sha256:" + "6".repeat(64));
      this.deleteTag(repo, "shared-layers", "dropped");
      // An index whose children are not untagged while the index is tagged...
      it.seedMultiPlatformImage(repo, "index-tagged", "v1");
      // ...and are, all three manifests of them, once its tag is gone.
      final var untaggedIndex = it.seedMultiPlatformImage(repo, "index-untagged", "v1");
      this.deleteTag(repo, "index-untagged", untaggedIndex.tag());
      // Pad the page well over 20 images.
      for (int i = 0; i < 20; i++) {
        it.seedImage(repo, "pad-" + i, "latest");
      }

      final var listed = this.listedImages(repo);

      assertThat(listed).hasSize(25);
      assertThat(listed.get("tagged")).containsEntry("untaggedManifestCount", 0);
      assertThat(listed.get("untagged-only")).containsEntry("untaggedManifestCount", 1);
      assertThat(listed.get("shared-layers")).containsEntry("untaggedManifestCount", 1);
      assertThat(listed.get("index-tagged")).containsEntry("untaggedManifestCount", 0);
      assertThat(listed.get("index-untagged")).containsEntry("untaggedManifestCount", 3);
      for (final var image : this.imageRepository.findAllByRepoId(repo.getId())) {
        final var expected = this.perImageUntagged(image.getId());
        final var row = listed.get(image.getName());

        assertThat(((Number) row.get("untaggedManifestCount")).longValue())
            .as("untagged manifests of %s", image.getName())
            .isEqualTo(expected[0])
            .isEqualTo(this.untaggedManifestFinder.findUntagged(image.getId()).size());
        assertThat(((Number) row.get("untaggedSize")).longValue())
            .as("untagged size of %s", image.getName())
            .isEqualTo(expected[1]);
      }
    }

    @Test
    @DisplayName("the detail of one image carries the same untagged stats as its list row")
    void summaryMatchesTheListRow() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      DockerImageControllerIT.this.seedImage(repo, "other", "latest");
      this.deleteTag(repo, "app", "latest");

      final var body =
          BareBodyAssertions.expectBare(
              DockerImageControllerIT.this.perform(
                  get("/api/docker/images/%s/app".formatted(repo.getName()))
                      .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
      final var summary = data(body);
      final var listed = this.listedImages(repo).get("app");

      assertThat(summary)
          .containsEntry("untaggedManifestCount", 1)
          .containsEntry("untaggedManifestCount", listed.get("untaggedManifestCount"));
      assertThat(((Number) summary.get("untaggedSize")).longValue())
          .isEqualTo(((Number) listed.get("untaggedSize")).longValue())
          .isEqualTo(CONFIG_JSON.length() + LAYER_SIZE);
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
          "loginRequired",
          "unAuthorized",
          "Please log in: the credentials are missing or invalid, or the account is gone.");
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
    @DisplayName("deletes a tag with 204 while keeping the image")
    void deletesTag() throws Exception {
      final var repo = DockerImageControllerIT.this.dockerRepo();
      final var fixture = DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      BareBodyAssertions.expectNoContent(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app/tags/latest".formatted(repo.getName()))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
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
          DockerImageControllerIT.this.imageService.getImageInfoByRepoIdAndName(
              repo.getId(), fixture.imageName);
      assertThat(DockerImageControllerIT.this.manifestRepository.findAllByImageId(image.getId()))
          .extracting(manifest -> manifest.getDigest())
          .containsExactly(fixture.manifestDigest);
      assertThat(storageDirOf(repo).resolve("manifests").resolve(fixture.manifestDigest)).exists();
      BareBodyAssertions.expectBare(
          DockerImageControllerIT.this.perform(
              get("/api/docker/images/%s/app/manifests/%s"
                      .formatted(repo.getName(), fixture.manifestDigest))
                  .header(AUTHORIZATION, DockerImageControllerIT.this.adminBearerToken())));
    }

    @Test
    @DisplayName("deletes the orphan layers and an image, both 204")
    void deletesImageAndOrphans() throws Exception {
      final var repo = DockerImageControllerIT.this.privateDockerRepo();
      DockerImageControllerIT.this.seedImage(repo, "app", "latest");
      final var token = DockerImageControllerIT.this.adminBearerToken();
      BareBodyAssertions.expectNoContent(
          DockerImageControllerIT.this.perform(
              delete("/api/repos/%s/docker/orphan-layers".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)));
      BareBodyAssertions.expectNoContent(
          DockerImageControllerIT.this.perform(
              delete("/api/docker/images/%s/app".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)));
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
              delete("/api/repos/%s/docker/orphan-layers".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)),
          HttpStatus.FORBIDDEN,
          "accessDenied",
          "accessDenied",
          "Access Denied. Please check your credentials.");
    }
  }

  @Nested
  @DisplayName("routes without a literal where a repo name can sit (RPS-1781)")
  class LiteralFreeRoutes {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "get:/api/docker/images/%s/app/summary",
          "delete:/api/docker/images/blobs/%s/orphan-layers",
          "delete:/api/docker/images/manifests/%s/untagged"
        })
    @DisplayName("the old routes are gone: 404")
    void oldRoutesAre404(final String route) throws Exception {
      final var it = DockerImageControllerIT.this;
      final var repo = it.dockerRepo();
      it.seedImage(repo, "app", "latest");
      final var path = route.substring(route.indexOf(':') + 1).formatted(repo.getName());
      final var request = route.startsWith("get:") ? get(path) : delete(path);

      it.perform(request.header(AUTHORIZATION, it.adminBearerToken()))
          .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a repo named blobs and images named like route literals do not collide")
    void literalNamesDoNotCollide() throws Exception {
      final var it = DockerImageControllerIT.this;
      final var repo = it.seedRepo(RepoType.DOCKER, "blobs");
      final var token = it.adminBearerToken();
      for (final var name : List.of("manifests", "tags", "configs", "blobs", "untagged")) {
        it.seedImage(repo, name, "latest");
      }

      for (final var name : List.of("manifests", "tags", "configs", "blobs", "untagged")) {
        final var image =
            BareBodyAssertions.expectBare(
                it.perform(
                    get("/api/docker/images/%s/%s".formatted(repo.getName(), name))
                        .header(AUTHORIZATION, token)));
        assertThat(data(image)).containsEntry("name", name);
        final var tags =
            BareBodyAssertions.expectBare(
                it.perform(
                    get("/api/docker/images/%s/%s/tags".formatted(repo.getName(), name))
                        .header(AUTHORIZATION, token)));
        assertThat(data(tags)).containsKey("content");
      }

      BareBodyAssertions.expectNoContent(
          it.perform(
              delete("/api/repos/%s/docker/orphan-layers".formatted(repo.getName()))
                  .header(AUTHORIZATION, token)));
      final var cleaned =
          BareBodyAssertions.expectBare(
              it.perform(
                  delete("/api/repos/%s/docker/untagged-manifests".formatted(repo.getName()))
                      .header(AUTHORIZATION, token)));
      assertThat(data(cleaned)).containsEntry("deletedManifests", 0);
    }

    @Test
    @DisplayName("an image with several segments is addressed by the image query and the - name")
    void multiSegmentImageUsesTheImageQuery() throws Exception {
      final var it = DockerImageControllerIT.this;
      final var repo = it.dockerRepo();
      final var token = it.adminBearerToken();
      it.seedImage(repo, "team/app", "latest");

      final var image =
          BareBodyAssertions.expectBare(
              it.perform(
                  get("/api/docker/images/%s/-".formatted(repo.getName()))
                      .param("image", "team/app")
                      .header(AUTHORIZATION, token)));
      assertThat(data(image)).containsEntry("name", "team/app");

      final var tags =
          BareBodyAssertions.expectBare(
              it.perform(
                  get("/api/docker/images/%s/-/tags".formatted(repo.getName()))
                      .param("image", "team/app")
                      .header(AUTHORIZATION, token)));
      assertThat((List<?>) data(tags).get("content")).hasSize(1);

      final var detail =
          BareBodyAssertions.expectBare(
              it.perform(
                  get("/api/docker/images/%s/-/tags/latest".formatted(repo.getName()))
                      .param("image", "team/app")
                      .header(AUTHORIZATION, token)));
      assertThat(data(detail)).containsEntry("imageName", "team/app");

      BareBodyAssertions.expectNoContent(
          it.perform(
              delete("/api/docker/images/%s/-".formatted(repo.getName()))
                  .param("image", "team/app")
                  .header(AUTHORIZATION, token)));
      it.perform(
              get("/api/docker/images/%s/-".formatted(repo.getName()))
                  .param("image", "team/app")
                  .header(AUTHORIZATION, token))
          .andExpect(status().isNotFound());
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
          .andExpect(jsonPath("$.content[0].name").value("app"));
      this.list(repo, IMAGES, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].name").value("other"));
      this.list(repo, TAGS, "sort", "name,asc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].name").value("latest"));
      this.list(repo, TAGS, "sort", "name,desc")
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[0].name").value("stable"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {IMAGES, TAGS, MANIFESTS})
    @DisplayName("filters by q only: name, the old name of the filter, is an unknown parameter")
    void filtersByQOnly(final String path) throws Exception {
      final var repo = this.seededRepo();

      PagingAssertions.expectFilterIsQ(
          this.list(repo, path, "page", "0"),
          this.list(repo, path, "name", PagingAssertions.NO_MATCH),
          this.list(repo, path, "q", PagingAssertions.NO_MATCH));
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
