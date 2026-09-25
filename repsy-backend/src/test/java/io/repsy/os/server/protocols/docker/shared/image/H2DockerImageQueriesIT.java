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
package io.repsy.os.server.protocols.docker.shared.image;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.H2IntegrationTest;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.entities.ManifestChild;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The SQL RPS-1350 added to the Docker image list, run on embedded H2 as well as on PostgreSQL: the
 * image insert that skips an existing row, and the recursive queries that follow an index through
 * the indexes it lists.
 */
@DisplayName("Docker image queries on embedded H2 (RPS-1350)")
class H2DockerImageQueriesIT extends H2IntegrationTest {

  private static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  private static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";

  @Autowired private RepoRepository repoRepository;
  @Autowired private ImageRepository imageRepository;
  @Autowired private ImageTxService imageTxService;
  @Autowired private LayerRepository layerRepository;
  @Autowired private ManifestRepository manifestRepository;
  @Autowired private ManifestChildRepository manifestChildRepository;
  @Autowired private TagRepository tagRepository;

  @Test
  @DisplayName("creating an image twice answers the same image")
  void findOrCreateImageIsIdempotent() {
    final var repo = this.repo("h2dockerimage");

    final var first = this.imageTxService.findOrCreateImage(repo.getId(), "app");
    final var second = this.imageTxService.findOrCreateImage(repo.getId(), "app");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(this.imageRepository.findAllByRepoId(repo.getId())).hasSize(1);
  }

  @Test
  @DisplayName("an index is followed through the indexes it lists, for the size and the untagged")
  void nestedIndexesAreFollowed() {
    final var repo = this.repo("h2dockernested");
    final var image =
        this.imageRepository
            .findById(this.imageTxService.findOrCreateImage(repo.getId(), "app").getId())
            .orElseThrow();
    final var leafLayer = this.layer(repo, "leaf", 100);
    final var plainLayer = this.layer(repo, "plain", 7);
    final var leaf = this.manifest(image, "leaf", OCI_MANIFEST, leafLayer);
    final var inner = this.manifest(image, "inner", OCI_INDEX);
    final var outer = this.manifest(image, "outer", OCI_INDEX);
    final var plain = this.manifest(image, "plain", OCI_MANIFEST, plainLayer);
    this.manifestChildRepository.save(new ManifestChild(inner, leaf, "linux/amd64"));
    this.manifestChildRepository.save(new ManifestChild(outer, inner, "unknown/unknown"));
    this.tag(image, outer, "top");
    this.manifestChildRepository.flush();

    // The leaf is two indexes below the tag: reached, so not untagged, and part of the size.
    assertThat(this.layerRepository.sumDistinctSizeByImageId(repo.getId(), image.getId()))
        .isEqualTo(100);
    var untagged = this.imageRepository.findUntaggedStatsByImageId(image.getId());
    assertThat(untagged.getManifestCount()).isEqualTo(1);
    assertThat(untagged.getSize()).isEqualTo(7);

    // The tag moves to the plain manifest: the two indexes and the leaf are untagged.
    final var tag = this.tagRepository.findByImageIdAndName(image.getId(), "top").orElseThrow();
    tag.setManifest(plain);
    tag.setDigest(plain.getDigest());
    this.tagRepository.saveAndFlush(tag);

    untagged = this.imageRepository.findUntaggedStatsByImageId(image.getId());
    assertThat(untagged.getManifestCount()).isEqualTo(3);
    assertThat(untagged.getSize()).isEqualTo(100);
    assertThat(this.layerRepository.sumDistinctSizeByImageId(repo.getId(), image.getId()))
        .isEqualTo(7);
  }

  private Repo repo(final String name) {
    final var repo = new Repo();
    repo.setName(name);
    repo.setType(RepoType.DOCKER);
    repo.setPrivateRepo(false);
    repo.setAllowOverride(true);
    repo.setSearchable(true);
    repo.setDiskUsage(0);
    repo.setCreatedAt(Instant.now());
    return this.repoRepository.saveAndFlush(repo);
  }

  private Layer layer(final Repo repo, final String name, final long size) {
    final var layer = new Layer();
    layer.setRepo(repo);
    layer.setDigest("sha256:" + name);
    layer.setSize(size);
    layer.setMediaType("application/octet-stream");
    return this.layerRepository.saveAndFlush(layer);
  }

  private Manifest manifest(
      final io.repsy.os.server.protocols.docker.shared.image.entities.Image image,
      final String name,
      final String mediaType,
      final Layer... layers) {
    final var manifest = new Manifest();
    manifest.setImage(image);
    manifest.setPlatform("linux/amd64");
    manifest.setDigest("sha256:" + name);
    manifest.setMediaType(mediaType);
    manifest.setSchemaVersion(2);
    manifest.setLayers(Set.of(layers));
    return this.manifestRepository.saveAndFlush(manifest);
  }

  private void tag(
      final io.repsy.os.server.protocols.docker.shared.image.entities.Image image,
      final Manifest manifest,
      final String name) {
    final var tag = new Tag();
    tag.setImage(image);
    tag.setManifest(manifest);
    tag.setName(name);
    tag.setDigest(manifest.getDigest());
    tag.setMediaType(manifest.getMediaType());
    tag.setPlatform(manifest.getPlatform());
    this.tagRepository.saveAndFlush(tag);
  }
}
