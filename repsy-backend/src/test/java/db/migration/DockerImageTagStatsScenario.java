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
package db.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Docker images the RPS-2120 backfill (V0043) is tested on, at V0041, and what it must make of
 * them. Shared by {@code V0043DockerImageTagStatsBackfillTest} (H2) and {@code
 * V0043DockerImageTagStatsBackfillIT} (PostgreSQL).
 *
 * <p>Four hand-made images: one with two tags, one with a single tag, one that stores an untagged
 * manifest only and one without any row below it. {@code bulkImages} more images, every fourth
 * tagged, make the PostgreSQL backfill run several batches (random ids, so the id order is not the
 * creation order).
 */
public final class DockerImageTagStatsScenario {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  public final UUID repoId = UUID.randomUUID();
  public final UUID twoTags = UUID.randomUUID();
  public final UUID oneTag = UUID.randomUUID();
  public final UUID untaggedOnly = UUID.randomUUID();
  public final UUID empty = UUID.randomUUID();

  private final JdbcTemplate jdbc;
  private final int bulkImages;

  public DockerImageTagStatsScenario(final JdbcTemplate jdbc, final int bulkImages) {
    this.jdbc = jdbc;
    this.bulkImages = bulkImages;
  }

  /** Seeds the images; the schema must be at V0041. */
  public void seed() {
    this.jdbc.update(
        "insert into \"public\".\"repo\" (\"id\", \"name\", \"type\", \"allow_override\", \"created_at\")"
            + " values (?, ?, 'DOCKER', true, ?)",
        this.repoId,
        "docker-" + this.repoId.toString().substring(0, 8),
        Timestamp.from(BASE));

    this.image(this.twoTags, "two-tags");
    this.image(this.oneTag, "one-tag");
    this.image(this.untaggedOnly, "untagged-only");
    this.image(this.empty, "empty");

    this.tag(this.twoTags, this.manifest(this.twoTags, "a"), "v1", 1);
    this.tag(this.twoTags, this.manifest(this.twoTags, "b"), "v2", 5);
    this.tag(this.oneTag, this.manifest(this.oneTag, "c"), "latest", 3);
    this.manifest(this.untaggedOnly, "d");

    final var images = new ArrayList<Object[]>();
    final var manifests = new ArrayList<Object[]>();
    final var tags = new ArrayList<Object[]>();
    for (var n = 0; n < this.bulkImages; n++) {
      final var id = UUID.randomUUID();
      images.add(new Object[] {id, this.repoId, "bulk-" + n, Timestamp.from(BASE)});
      if (n % 4 == 0) {
        final var manifestId = UUID.randomUUID();
        manifests.add(new Object[] {manifestId, id, "sha256:bulk" + n});
        tags.add(
            new Object[] {
              UUID.randomUUID(),
              id,
              manifestId,
              "latest",
              "sha256:bulk" + n,
              Timestamp.from(BASE.plusSeconds(10L + n))
            });
      }
    }
    this.jdbc.batchUpdate(
        "insert into \"public\".\"docker_image\" (\"id\", \"repo_id\", \"name\", \"created_at\")"
            + " values (?, ?, ?, ?)",
        images);
    this.jdbc.batchUpdate(
        "insert into \"public\".\"docker_manifest\" (\"id\", \"image_id\", \"platform\", \"digest\","
            + " \"media_type\", \"schema_version\") values (?, ?, 'linux/amd64', ?, 'm', 2)",
        manifests);
    this.jdbc.batchUpdate(
        "insert into \"public\".\"docker_tag\" (\"id\", \"image_id\", \"manifest_id\", \"name\","
            + " \"digest\", \"media_type\", \"platform\", \"created_at\")"
            + " values (?, ?, ?, ?, ?, 'm', 'linux/amd64', ?)",
        tags);
  }

  /** Asserts what the backfill made of the images. */
  public void assertBackfilled() {
    assertThat(this.stats(this.twoTags)).containsExactly(2, Timestamp.from(BASE.plusSeconds(5)));
    assertThat(this.stats(this.oneTag)).containsExactly(1, Timestamp.from(BASE.plusSeconds(3)));
    assertThat(this.stats(this.untaggedOnly)).containsExactly(0, null);
    assertThat(this.stats(this.empty)).containsExactly(0, null);
    assertThat(
            this.jdbc.queryForObject(
                """
                select count(*) from "public"."docker_image" i
                where i."tag_count" <> (
                    select count(*) from "public"."docker_tag" t where t."image_id" = i."id")
                  or (i."last_tag_at" is null) <> (not exists (
                    select 1 from "public"."docker_tag" t where t."image_id" = i."id"))
                  or i."last_tag_at" <> (
                    select max(t."created_at") from "public"."docker_tag" t
                    where t."image_id" = i."id")""",
                Long.class))
        .as("images whose stored stats disagree with their tags")
        .isZero();
    assertThat(
            this.jdbc.queryForObject(
                "select count(*) from \"public\".\"docker_image\" where \"repo_id\" = ?"
                    + " and \"tag_count\" > 0",
                Long.class,
                this.repoId))
        .as("tagged images")
        .isEqualTo(2L + (this.bulkImages + 3) / 4);
  }

  private java.util.List<Object> stats(final UUID imageId) {
    return this.jdbc.queryForObject(
        "select \"tag_count\", \"last_tag_at\" from \"public\".\"docker_image\" where \"id\" = ?",
        (rows, n) -> {
          final var values = new ArrayList<Object>();
          values.add(rows.getInt(1));
          values.add(rows.getTimestamp(2));
          return values;
        },
        imageId);
  }

  private void image(final UUID id, final String name) {
    this.jdbc.update(
        "insert into \"public\".\"docker_image\" (\"id\", \"repo_id\", \"name\", \"created_at\")"
            + " values (?, ?, ?, ?)",
        id,
        this.repoId,
        name,
        Timestamp.from(BASE));
  }

  private UUID manifest(final UUID imageId, final String digest) {
    final var id = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"public\".\"docker_manifest\" (\"id\", \"image_id\", \"platform\", \"digest\","
            + " \"media_type\", \"schema_version\") values (?, ?, 'linux/amd64', ?, 'm', 2)",
        id,
        imageId,
        "sha256:" + digest);
    return id;
  }

  private void tag(final UUID imageId, final UUID manifestId, final String name, final int second) {
    this.jdbc.update(
        "insert into \"public\".\"docker_tag\" (\"id\", \"image_id\", \"manifest_id\", \"name\","
            + " \"digest\", \"media_type\", \"platform\", \"created_at\")"
            + " values (?, ?, ?, ?, 'sha256:x', 'm', 'linux/amd64', ?)",
        UUID.randomUUID(),
        imageId,
        manifestId,
        name,
        Timestamp.from(BASE.plusSeconds(second)));
  }
}
