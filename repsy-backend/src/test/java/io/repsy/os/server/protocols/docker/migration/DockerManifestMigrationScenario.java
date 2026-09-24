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
package io.repsy.os.server.protocols.docker.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Docker data an installation has before V0024 (RPS-1216: content-addressed manifests) and what
 * the migration has to make of it. The same scenario runs against PostgreSQL and H2, so the two
 * migration scripts are held to the same result.
 *
 * <p>The legacy shapes, all written the way the code before RPS-1216 wrote them:
 *
 * <ul>
 *   <li>a single-platform tag, and a tag that was overridden (only its current digest is left);
 *   <li>two tags with the same digest, each with a manifest row of its own (the row of the first
 *       tag has no layer links, the copy has them);
 *   <li>a multi-platform tag: the index row, one tracking row per child (named after the child's
 *       digest, without a file of its own), the child pushed under its own tag as well, and the
 *       other child pushed only by digest;
 *   <li>the tags named after a digest that a digest-pushed index and a sha512 push left behind;
 *   <li>a manifest row of a failed push that belongs to no tag, and a tag whose manifest is gone;
 *   <li>the same digest in a second image, and two tag rows of one name that differ in platform.
 * </ul>
 */
public final class DockerManifestMigrationScenario {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static final String INDEX = "application/vnd.oci.image.index.v1+json";
  private static final String IMAGE_MANIFEST = "application/vnd.oci.image.manifest.v1+json";

  private final JdbcTemplate jdbc;

  private final UUID repoId = UUID.randomUUID();
  private final UUID appId = UUID.randomUUID();
  private final UUID otherId = UUID.randomUUID();
  private final UUID layer1 = UUID.randomUUID();
  private final UUID layer2 = UUID.randomUUID();

  private final String d1 = digest('1');
  private final String d3 = digest('3');
  private final String d4 = digest('4');
  private final String dl = digest('a');
  private final String dc1 = digest('b');
  private final String dc2 = digest('c');
  private final String dl2 = digest('d');
  private final String dSha512Push = digest('e');
  private final String pseudo512 = "sha512:" + "e".repeat(128);
  private final String dx = digest('f');
  private final String dGhost = digest('9');
  private final String dDup1 = digest('7');
  private final String dDup2 = digest('8');

  public DockerManifestMigrationScenario(final JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String digest(final char c) {
    return "sha256:" + String.valueOf(c).repeat(64);
  }

  private static Timestamp at(final int minutes) {
    return Timestamp.from(T0.plus(minutes, ChronoUnit.MINUTES));
  }

  /** Writes the legacy data; the schema must be at V0023. */
  public void seed() {
    this.jdbc.update(
        "insert into \"repo\" (\"id\", \"name\", \"type\", \"created_at\") values (?, ?, 'DOCKER', ?)",
        this.repoId,
        "migrationrepo",
        at(0));
    this.image(this.appId, "app");
    this.image(this.otherId, "other");
    this.layer(this.layer1, digest('5'));
    this.layer(this.layer2, digest('6'));

    // single-platform tag
    final var v1 = this.tag(this.appId, "v1", this.d1, IMAGE_MANIFEST, "linux/amd64", 1);
    this.manifest(this.platform(v1, "linux/amd64"), "v1", this.d1, "linux/amd64", IMAGE_MANIFEST, 1)
        .layers(this.layer1);

    // an overridden tag: the row was rewritten in place, so only the new digest is left
    final var v2 = this.tag(this.appId, "v2", this.d3, IMAGE_MANIFEST, "linux/amd64", 2);
    this.manifest(this.platform(v2, "linux/amd64"), "v2", this.d3, "linux/amd64", IMAGE_MANIFEST, 2)
        .layers(this.layer1, this.layer2);

    // two tags, one digest, each with its own row; the copy has the layer links
    final var tagA = this.tag(this.appId, "a", this.d4, IMAGE_MANIFEST, "linux/amd64", 3);
    final var tagB = this.tag(this.appId, "b", this.d4, IMAGE_MANIFEST, "linux/amd64", 4);
    this.manifest(
        this.platform(tagA, "linux/amd64"), "a", this.d4, "linux/amd64", IMAGE_MANIFEST, 3);
    this.manifest(
            this.platform(tagB, "linux/amd64"), "b", this.d4, "linux/amd64", IMAGE_MANIFEST, 4)
        .layers(this.layer2);

    // a multi-platform tag; the amd64 child also has a tag of its own, the arm64 child does not
    final var multi = this.tag(this.appId, "multi", this.dl, INDEX, "Multiplatform", 5);
    this.manifest(
        this.platform(multi, "Multiplatform"), "multi", this.dl, "Multiplatform", INDEX, 5);
    this.manifest(
            this.platform(multi, "linux/amd64"),
            this.dc1,
            this.dc1,
            "linux/amd64",
            IMAGE_MANIFEST,
            7)
        .layers(this.layer1);
    this.manifest(
            this.platform(multi, "linux/arm64"),
            this.dc2,
            this.dc2,
            "linux/arm64",
            IMAGE_MANIFEST,
            7)
        .layers(this.layer2);
    final var amd64Tag =
        this.tag(this.appId, "amd64tag", this.dc1, IMAGE_MANIFEST, "linux/amd64", 6);
    this.manifest(
            this.platform(amd64Tag, "linux/amd64"),
            "amd64tag",
            this.dc1,
            "linux/amd64",
            IMAGE_MANIFEST,
            6)
        .layers(this.layer1);

    // pseudo tags: an index pushed by its digest, and a sha512 push
    final var pseudo256 = this.tag(this.appId, this.dl2, this.dl2, INDEX, "Multiplatform", 8);
    this.manifest(
        this.platform(pseudo256, "Multiplatform"), this.dl2, this.dl2, "Multiplatform", INDEX, 8);
    final var sha512Tag =
        this.tag(this.appId, this.pseudo512, this.dSha512Push, IMAGE_MANIFEST, "linux/amd64", 9);
    this.manifest(
        this.platform(sha512Tag, "linux/amd64"),
        this.pseudo512,
        this.dSha512Push,
        "linux/amd64",
        IMAGE_MANIFEST,
        9);

    // the row of a failed push, which belongs to no tag, and a tag without a manifest
    this.manifest(null, "left-over", this.dx, "linux/amd64", IMAGE_MANIFEST, 10)
        .layers(this.layer1);
    this.tag(this.appId, "ghost", this.dGhost, IMAGE_MANIFEST, "linux/amd64", 11);

    // two tag rows of one name (an older version could leave both); the newer one wins
    final var dupOld = this.tag(this.appId, "dup", this.dDup1, IMAGE_MANIFEST, "linux/amd64", 12);
    final var dupNew = this.tag(this.appId, "dup", this.dDup2, IMAGE_MANIFEST, "linux/arm64", 13);
    this.manifest(
        this.platform(dupOld, "linux/amd64"), "dup", this.dDup1, "linux/amd64", IMAGE_MANIFEST, 12);
    this.manifest(
        this.platform(dupNew, "linux/arm64"), "dup", this.dDup2, "linux/arm64", IMAGE_MANIFEST, 13);

    // the same digest in another image is another manifest
    final var otherTag = this.tag(this.otherId, "v1", this.d1, IMAGE_MANIFEST, "linux/amd64", 14);
    this.manifest(
        this.platform(otherTag, "linux/amd64"), "v1", this.d1, "linux/amd64", IMAGE_MANIFEST, 14);
  }

  /** What the migrated data has to look like; the schema must be at V0024 or later. */
  public void verify() {
    // One manifest row per (image, digest): 13 distinct digests in app (the copy, the tracking row
    // of the amd64 child and the pseudo-tag rows folded or kept), 1 in other, none left over.
    assertThat(this.digests(this.appId))
        .containsExactlyInAnyOrder(
            this.d1,
            this.d3,
            this.d4,
            this.dl,
            this.dc1,
            this.dc2,
            this.dl2,
            this.dSha512Push,
            this.dDup1,
            this.dDup2);
    assertThat(this.digests(this.otherId)).containsExactly(this.d1);
    assertThat(this.count("select count(*) from \"docker_manifest\" where \"digest\" = ?", this.dx))
        .as("the row of a failed push is gone")
        .isZero();
    assertThat(this.count("select count(*) from \"docker_manifest\" where \"image_id\" is null"))
        .isZero();

    // storage_name keeps the reference the legacy file name was generated from; the row of the
    // original push wins over the copy and the tracking row
    assertThat(this.storageName(this.appId, this.d1)).isEqualTo("v1");
    assertThat(this.storageName(this.otherId, this.d1)).isEqualTo("v1");
    assertThat(this.storageName(this.appId, this.d4)).isEqualTo("a");
    assertThat(this.storageName(this.appId, this.dc1)).isEqualTo("amd64tag");
    assertThat(this.storageName(this.appId, this.dc2)).isEqualTo(this.dc2);
    assertThat(this.storageName(this.appId, this.dl)).isEqualTo("multi");
    assertThat(this.storageName(this.appId, this.dl2)).isEqualTo(this.dl2);
    assertThat(this.storageName(this.appId, this.dSha512Push)).isEqualTo(this.pseudo512);
    assertThat(
            this.jdbc.queryForList(
                "select \"digest\" from \"docker_manifest\" where \"digest_sha512\" is not null",
                String.class))
        .as(
            "sha512 cannot be computed in SQL; only the manifest of a sha512 push keeps the digest"
                + " its tag was named after, the repair service fills the others")
        .containsExactly(this.dSha512Push);
    assertThat(
            this.jdbc.queryForObject(
                "select \"digest_sha512\" from \"docker_manifest\" where \"digest\" = ?",
                String.class,
                this.dSha512Push))
        .isEqualTo(this.pseudo512);

    // the survivor of the two rows of one digest takes over the layer links it lacks
    assertThat(this.layerDigestsOf(this.appId, this.d4)).containsExactly(digest('6'));

    // tags: pointers to the row of their digest; pseudo tags, the dangling tag and the older of two
    // tags of one name are gone
    assertThat(this.tagNames(this.appId))
        .containsExactlyInAnyOrder("v1", "v2", "a", "b", "multi", "amd64tag", "dup");
    assertThat(this.tagNames(this.otherId)).containsExactly("v1");
    assertThat(
            this.jdbc.queryForList(
                "select t.\"name\" from \"docker_tag\" t join \"docker_manifest\" m on m.\"id\" = t.\"manifest_id\""
                    + " where m.\"digest\" <> t.\"digest\" or m.\"image_id\" <> t.\"image_id\"",
                String.class))
        .as("a tag points at the row of its own digest in its own image")
        .isEmpty();
    assertThat(this.tagDigest(this.appId, "dup"))
        .as("the newer of two tag rows of one name wins")
        .isEqualTo(this.dDup2);
    assertThat(this.tagDigest(this.appId, "a")).isEqualTo(this.tagDigest(this.appId, "b"));
    assertThat(this.tagManifestId(this.appId, "a")).isEqualTo(this.tagManifestId(this.appId, "b"));

    // the untagged manifests are kept: pseudo-tag rows and the older duplicate tag's manifest
    assertThat(
            this.count(
                "select count(*) from \"docker_manifest\" m where m.\"image_id\" = ? and not exists"
                    + " (select 1 from \"docker_tag\" t where t.\"manifest_id\" = m.\"id\")",
                this.appId))
        .isPositive();

    // the index references its two children, under the platforms the index names them
    final var edges =
        this.jdbc.queryForList(
            "select c.\"platform\" as platform, m.\"digest\" as digest, m.\"storage_name\" as storage_name"
                + " from \"docker_manifest_child\" c"
                + " join \"docker_manifest\" p on p.\"id\" = c.\"parent_id\""
                + " join \"docker_manifest\" m on m.\"id\" = c.\"child_id\""
                + " where p.\"digest\" = ? and p.\"image_id\" = ?",
            this.dl,
            this.appId);
    assertThat(edges)
        .extracting(edge -> edge.get("platform") + "=" + edge.get("digest"))
        .containsExactlyInAnyOrder("linux/amd64=" + this.dc1, "linux/arm64=" + this.dc2);
    assertThat(this.count("select count(*) from \"docker_manifest_child\""))
        .as("nothing else has children")
        .isEqualTo(2);

    this.verifyConstraints();
  }

  private void verifyConstraints() {
    final var image = this.appId;
    final var manifest =
        this.jdbc.queryForObject(
            "select \"id\" from \"docker_manifest\" where \"image_id\" = ? and \"digest\" = ?",
            UUID.class,
            image,
            this.d1);

    // one manifest per (image, digest) and one tag per (image, name)
    assertThatThrownBy(() -> this.insertManifest(UUID.randomUUID(), image, this.d1))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> this.insertTag(UUID.randomUUID(), image, "v1", this.d1, manifest))
        .isInstanceOf(DataAccessException.class);
    // a tag needs a manifest
    assertThatThrownBy(() -> this.insertTag(UUID.randomUUID(), image, "orphan", this.d1, null))
        .isInstanceOf(DataAccessException.class);

    // a new manifest, a tag, and a second tag of another platform; both directions of the cascade
    final var fresh = UUID.randomUUID();
    this.insertManifest(fresh, image, digest('0'));
    final var freshTag = UUID.randomUUID();
    this.insertTag(freshTag, image, "fresh", digest('0'), fresh);

    this.jdbc.update("delete from \"docker_tag\" where \"id\" = ?", freshTag);
    assertThat(this.count("select count(*) from \"docker_manifest\" where \"id\" = ?", fresh))
        .as("deleting a tag never deletes its manifest")
        .isEqualTo(1);

    this.insertTag(freshTag, image, "fresh", digest('0'), fresh);
    this.jdbc.update("delete from \"docker_manifest\" where \"id\" = ?", fresh);
    assertThat(this.count("select count(*) from \"docker_tag\" where \"id\" = ?", freshTag))
        .as("deleting a manifest deletes the tags that point at it")
        .isZero();

    // deleting the image takes its tags, manifests, layer links and edges along
    this.jdbc.update("delete from \"docker_image\" where \"id\" = ?", image);
    assertThat(this.count("select count(*) from \"docker_tag\" where \"image_id\" = ?", image))
        .isZero();
    assertThat(this.count("select count(*) from \"docker_manifest\" where \"image_id\" = ?", image))
        .isZero();
    assertThat(this.count("select count(*) from \"docker_manifest_child\"")).isZero();
    assertThat(this.digests(this.otherId))
        .as("other images are untouched")
        .containsExactly(this.d1);
  }

  // ---------------------------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------------------------

  private void image(final UUID id, final String name) {
    this.jdbc.update(
        "insert into \"docker_image\" (\"id\", \"repo_id\", \"name\", \"created_at\") values (?, ?, ?, ?)",
        id,
        this.repoId,
        name,
        at(0));
  }

  private void layer(final UUID id, final String digest) {
    this.jdbc.update(
        "insert into \"docker_layer\" (\"id\", \"repo_id\", \"digest\", \"size\", \"media_type\", \"created_at\")"
            + " values (?, ?, ?, 10, 'application/octet-stream', ?)",
        id,
        this.repoId,
        digest,
        at(0));
  }

  private UUID tag(
      final UUID image,
      final String name,
      final String digest,
      final String mediaType,
      final String platform,
      final int minute) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"docker_tag\" (\"id\", \"image_id\", \"name\", \"digest\", \"media_type\", \"platform\","
            + " \"created_at\", \"last_updated_at\") values (?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        image,
        name,
        digest,
        mediaType,
        platform,
        at(minute),
        at(minute));

    return id;
  }

  private UUID platform(final UUID tag, final String platform) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"docker_tag_platform\" (\"id\", \"tag_id\", \"platform\", \"created_at\") values (?, ?, ?, ?)",
        id,
        tag,
        platform,
        at(0));

    return id;
  }

  /** Inserts a legacy manifest row (under {@code tagPlatform}, or none) and lets links be added. */
  private ManifestRow manifest(
      final UUID tagPlatform,
      final String name,
      final String digest,
      final String platform,
      final String mediaType,
      final int minute) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"docker_manifest\" (\"id\", \"tag_platform_id\", \"name\", \"platform\", \"digest\","
            + " \"media_type\", \"schema_version\", \"created_at\", \"last_updated_at\")"
            + " values (?, ?, ?, ?, ?, ?, 2, ?, ?)",
        id,
        tagPlatform,
        name,
        platform,
        digest,
        mediaType,
        at(minute),
        at(minute));

    return new ManifestRow(id);
  }

  private final class ManifestRow {

    private final UUID id;

    private ManifestRow(final UUID id) {
      this.id = id;
    }

    void layers(final UUID... layers) {
      for (final var layer : layers) {
        DockerManifestMigrationScenario.this.jdbc.update(
            "insert into \"docker_manifest_layer\" (\"manifest_id\", \"layer_id\") values (?, ?)",
            this.id,
            layer);
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // queries
  // ---------------------------------------------------------------------------------------------

  private void insertManifest(final UUID id, final UUID image, final String digest) {
    this.jdbc.update(
        "insert into \"docker_manifest\" (\"id\", \"image_id\", \"platform\", \"digest\", \"media_type\","
            + " \"schema_version\") values (?, ?, 'linux/amd64', ?, ?, 2)",
        id,
        image,
        digest,
        IMAGE_MANIFEST);
  }

  private void insertTag(
      final UUID id,
      final UUID image,
      final String name,
      final String digest,
      final UUID manifest) {
    this.jdbc.update(
        "insert into \"docker_tag\" (\"id\", \"image_id\", \"name\", \"digest\", \"media_type\", \"platform\","
            + " \"manifest_id\") values (?, ?, ?, ?, ?, 'linux/amd64', ?)",
        id,
        image,
        name,
        digest,
        IMAGE_MANIFEST,
        manifest);
  }

  private long count(final String sql, final Object... args) {
    return this.jdbc.queryForObject(sql, Long.class, args);
  }

  private List<String> digests(final UUID image) {
    return this.jdbc.queryForList(
        "select \"digest\" from \"docker_manifest\" where \"image_id\" = ?", String.class, image);
  }

  private String storageName(final UUID image, final String digest) {
    return this.jdbc.queryForObject(
        "select \"storage_name\" from \"docker_manifest\" where \"image_id\" = ? and \"digest\" = ?",
        String.class,
        image,
        digest);
  }

  private Set<String> layerDigestsOf(final UUID image, final String digest) {
    return this.jdbc
        .queryForList(
            "select l.\"digest\" from \"docker_manifest_layer\" ml"
                + " join \"docker_manifest\" m on m.\"id\" = ml.\"manifest_id\""
                + " join \"docker_layer\" l on l.\"id\" = ml.\"layer_id\""
                + " where m.\"image_id\" = ? and m.\"digest\" = ?",
            String.class,
            image,
            digest)
        .stream()
        .collect(Collectors.toSet());
  }

  private List<String> tagNames(final UUID image) {
    return this.jdbc.queryForList(
        "select \"name\" from \"docker_tag\" where \"image_id\" = ?", String.class, image);
  }

  private String tagDigest(final UUID image, final String name) {
    return this.jdbc.queryForObject(
        "select \"digest\" from \"docker_tag\" where \"image_id\" = ? and \"name\" = ?",
        String.class,
        image,
        name);
  }

  private UUID tagManifestId(final UUID image, final String name) {
    return this.jdbc.queryForObject(
        "select \"manifest_id\" from \"docker_tag\" where \"image_id\" = ? and \"name\" = ?",
        UUID.class,
        image,
        name);
  }
}
