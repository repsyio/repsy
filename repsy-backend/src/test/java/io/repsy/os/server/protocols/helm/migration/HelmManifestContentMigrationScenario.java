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
package io.repsy.os.server.protocols.helm.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Helm OCI manifests an installation has before V0028 (RPS-1392: {@code
 * helm_oci_manifest.content} is text, no longer a large-object OID) and what the migration has to
 * make of them. The same scenario runs against PostgreSQL and H2, so the two scripts are held to
 * the same result.
 *
 * <p>Written the way the {@code @Lob} mapping wrote them, on PostgreSQL the column holds the OID of
 * a large object that carries the JSON. On H2 the clob is inline, so only the rows that are already
 * text exist there. The rows:
 *
 * <ul>
 *   <li>on PostgreSQL, a manifest whose content is a large-object OID, one with non-ASCII text and
 *       a large one (spread over several large-object pages), which must come back as the exact
 *       JSON with the large object unlinked;
 *   <li>a manifest that already is the JSON, which must stay as it is;
 *   <li>a content that is only digits but no large object (nothing of that OID exists), and one too
 *       long for an OID, which must be left alone and must not fail the cast;
 *   <li>on PostgreSQL, a large object that no row points to, which must not be unlinked.
 * </ul>
 */
public final class HelmManifestContentMigrationScenario {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final String MEDIA_TYPE = "application/vnd.oci.image.manifest.v1+json";

  private static final String PLAIN = "{\"schemaVersion\":2,\"note\":\"already text\"}";
  private static final String UNICODE = "{\"schemaVersion\":2,\"note\":\"Çalışma dizini, 日本語, ✓\"}";
  private static final String LARGE =
      "{\"schemaVersion\":2,\"layers\":[" + "{\"size\":1},".repeat(3000) + "{\"size\":2}]}";
  private static final String OID_LOOKALIKE = "4000000000";
  private static final String TOO_LONG_FOR_AN_OID = "99999999999";

  private final JdbcTemplate jdbc;
  private final boolean largeObjects;

  private final UUID repoId = UUID.randomUUID();
  private final UUID versionId = UUID.randomUUID();

  private int digests = 1;
  private long unrelatedLargeObject;
  private long asciiLargeObject;
  private long unicodeLargeObject;
  private long largeLargeObject;

  /**
   * @param jdbc the database, at V0027
   * @param largeObjects whether the database is PostgreSQL, where the old mapping wrote large
   *     objects
   */
  public HelmManifestContentMigrationScenario(final JdbcTemplate jdbc, final boolean largeObjects) {
    this.jdbc = jdbc;
    this.largeObjects = largeObjects;
  }

  private static Timestamp at(final int minutes) {
    return Timestamp.from(T0.plusSeconds(minutes * 60L));
  }

  private long largeObject(final String json) {
    return this.jdbc.queryForObject(
        "select lo_from_bytea(0, convert_to(?, 'UTF8'))", Long.class, json);
  }

  /** Writes the legacy data; the schema must be at V0027. */
  public void seed() {
    this.jdbc.update(
        "insert into \"repo\" (\"id\", \"name\", \"type\", \"created_at\") values (?, ?, 'HELM', ?)",
        this.repoId,
        "helmmigration",
        at(0));
    final var chartId = UUID.randomUUID();
    this.jdbc.update(
        "insert into \"helm_chart\" (\"id\", \"version_lock\", \"repo_id\", \"name\", \"created_at\")"
            + " values (?, 0, ?, 'payments', ?)",
        chartId,
        this.repoId,
        at(0));
    this.jdbc.update(
        "insert into \"helm_chart_version\" (\"id\", \"version_lock\", \"chart_id\", \"version\","
            + " \"digest\", \"size\", \"created_at\") values (?, 0, ?, '1.0.0', ?, 1, ?)",
        this.versionId,
        chartId,
        "sha256:" + "0".repeat(64),
        at(0));

    this.manifest("plain", PLAIN);
    this.manifest("oid-lookalike", OID_LOOKALIKE);
    this.manifest("too-long", TOO_LONG_FOR_AN_OID);

    if (this.largeObjects) {
      this.unrelatedLargeObject = this.largeObject("{\"unrelated\":true}");
      this.asciiLargeObject = this.largeObject("{\"schemaVersion\":2,\"note\":\"a large object\"}");
      this.unicodeLargeObject = this.largeObject(UNICODE);
      this.largeLargeObject = this.largeObject(LARGE);
      this.manifest("ascii-lo", String.valueOf(this.asciiLargeObject));
      this.manifest("unicode-lo", String.valueOf(this.unicodeLargeObject));
      this.manifest("large-lo", String.valueOf(this.largeLargeObject));
    } else {
      this.manifest("unicode-lo", UNICODE);
      this.manifest("large-lo", LARGE);
    }
  }

  private void manifest(final String reference, final String content) {
    this.jdbc.update(
        "insert into \"helm_oci_manifest\" (\"id\", \"version_lock\", \"repo_id\","
            + " \"chart_version_id\", \"name\", \"reference\", \"digest\", \"media_type\","
            + " \"content\", \"created_at\") values (?, 0, ?, ?, 'payments', ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        this.repoId,
        this.versionId,
        reference,
        "sha256:" + String.format("%064x", this.digests++),
        MEDIA_TYPE,
        content,
        at(1));
  }

  private String content(final String reference) {
    return this.jdbc.queryForObject(
        "select \"content\" from \"helm_oci_manifest\" where \"repo_id\" = ? and \"reference\" = ?",
        String.class,
        this.repoId,
        reference);
  }

  private boolean largeObjectExists(final long oid) {
    return this.jdbc.queryForObject(
            "select count(*) from pg_largeobject_metadata where \"oid\" = ?", Long.class, oid)
        == 1;
  }

  /** Checks the result; the schema must be at the latest version. */
  public void verify() {
    assertThat(this.content("plain")).isEqualTo(PLAIN);
    assertThat(this.content("oid-lookalike")).isEqualTo(OID_LOOKALIKE);
    assertThat(this.content("too-long")).isEqualTo(TOO_LONG_FOR_AN_OID);
    assertThat(this.content("unicode-lo")).isEqualTo(UNICODE);
    assertThat(this.content("large-lo")).isEqualTo(LARGE);

    if (this.largeObjects) {
      assertThat(this.content("ascii-lo"))
          .isEqualTo("{\"schemaVersion\":2,\"note\":\"a large object\"}");
      assertThat(this.largeObjectExists(this.asciiLargeObject)).isFalse();
      assertThat(this.largeObjectExists(this.unicodeLargeObject)).isFalse();
      assertThat(this.largeObjectExists(this.largeLargeObject)).isFalse();
      assertThat(this.largeObjectExists(this.unrelatedLargeObject)).isTrue();
    }
  }
}
