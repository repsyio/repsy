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
package io.repsy.os.server.protocols.ruby.protocol;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.PUBLISH_PATH;
import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.ruby.shared.utils.GemspecParser;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * Full-stack coverage of the RubyGems wire protocol served by the protocol router on the main port
 * (9090): {@code gem push}, download, index and {@code gem yank}.
 *
 * <p>The protocol path parser resolves the repo from {@code request.getServletPath()}. MockMvc
 * leaves that empty unless the test sets it, so a request that skips {@code protocolPort()} never
 * matches any handler and ends in {@code 404 unknownPath} even though the route is registered.
 */
@DisplayName("Ruby protocol /{repo}/api/v1/gems")
class RubyGemProtocolIT extends AbstractIntegrationTest {

  private ResultActions protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort()));
  }

  private ResultActions push(final String repoName, final byte[] gem, final String token)
      throws Exception {
    return this.protocol(
        post(PUBLISH_PATH, repoName)
            .header(AUTHORIZATION, token)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(gem));
  }

  @Test
  @DisplayName("POST /{repo}/api/v1/gems registers the pushed gem")
  void publishesGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));

    final var body =
        this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo("Successfully registered gem: pushed-gem (1.2.3)");
  }

  @Test
  @DisplayName("a pushed gem can be downloaded byte for byte")
  void downloadsPushedGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gem = gem("pushed-gem", "1.2.3");
    this.push(repo.getName(), gem, this.adminProtocolBearerToken()).andExpect(status().isOk());

    final var downloaded =
        this.protocol(
                get("/{repo}/gems/pushed-gem-1.2.3.gem", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(downloaded).isEqualTo(gem);
  }

  @Test
  @DisplayName("a pushed gem is listed in the compact index")
  void listsPushedGemInCompactIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var names =
        this.protocol(
                get("/{repo}/names", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(names).contains("pushed-gem");
  }

  /**
   * RPS-1234: the specs index files are served gzip-framed (RFC 1952), not bare zlib-deflated,
   * because real RubyGems clients ({@code Gem::Util.gunzip}, a {@code Zlib::GzipReader}) require
   * the gzip header/trailer/CRC. {@link GZIPInputStream} decoding the raw bytes pins that framing
   * directly, not just decodability of some deflate variant.
   */
  private byte[] fetchSpecsIndex(final Repo repo, final String fileName) throws Exception {
    return this.protocol(
            get("/{repo}/" + fileName, repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsByteArray();
  }

  private byte[] assertGzipMarshalIndexContaining(final byte[] raw, final String needle)
      throws Exception {
    assertThat(raw[0]).as("gzip magic byte 1").isEqualTo((byte) 0x1f);
    assertThat(raw[1]).as("gzip magic byte 2").isEqualTo((byte) 0x8b);

    final byte[] decoded;
    try (final var in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
      decoded = in.readAllBytes();
    }
    assertThat(decoded[0]).as("Marshal major version").isEqualTo((byte) 0x04);
    assertThat(decoded[1]).as("Marshal minor version").isEqualTo((byte) 0x08);
    assertThat(new String(decoded, StandardCharsets.ISO_8859_1)).contains(needle);
    return decoded;
  }

  @Test
  @DisplayName("GET /{repo}/specs.4.8.gz serves a gzip-framed Marshal index (RPS-1234)")
  void servesGzippedSpecsIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var raw = this.fetchSpecsIndex(repo, "specs.4.8.gz");

    this.assertGzipMarshalIndexContaining(raw, "pushed-gem");
  }

  @Test
  @DisplayName(
      "GET /{repo}/latest_specs.4.8.gz serves the latest non-prerelease version, gzip-framed"
          + " (RPS-1234)")
  void servesGzippedLatestSpecsIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var raw = this.fetchSpecsIndex(repo, "latest_specs.4.8.gz");

    this.assertGzipMarshalIndexContaining(raw, "pushed-gem");
  }

  @Test
  @DisplayName(
      "GET /{repo}/prerelease_specs.4.8.gz serves only prerelease versions, gzip-framed"
          + " (RPS-1234)")
  void servesGzippedPrereleaseSpecsIndex() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());
    this.push(repo.getName(), gem("pushed-gem", "2.0.0.pre1"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var raw = this.fetchSpecsIndex(repo, "prerelease_specs.4.8.gz");
    final var decoded = this.assertGzipMarshalIndexContaining(raw, "pushed-gem");

    assertThat(new String(decoded, StandardCharsets.ISO_8859_1)).contains("2.0.0.pre1");
  }

  @Test
  @DisplayName("DELETE /{repo}/api/v1/gems/yank yanks a pushed gem")
  void yanksPushedGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var token = this.adminProtocolBearerToken();
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), token).andExpect(status().isOk());

    final var body =
        this.protocol(
                delete("/{repo}/api/v1/gems/yank", repo.getName())
                    .header(AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("gem_name", "pushed-gem")
                    .param("version", "1.2.3"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).isEqualTo("Successfully yanked gem: pushed-gem (1.2.3)");
  }

  /**
   * RPS-1233: {@code quick/Marshal.4.8/*.gemspec.rz} had no registered handler ({@link
   * io.repsy.os.server.protocols.ruby.protocol.handlers.RubyGemspecHandler} was missing), so real
   * {@code gem install}/{@code gem fetch} clients always 404'd fetching the quick gemspec.
   */
  @Test
  @DisplayName("GET /{repo}/quick/Marshal.4.8/<gem>.gemspec.rz serves the deflated Marshal gemspec")
  void servesGemspecRz() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    final var body =
        this.protocol(
                get("/{repo}/quick/Marshal.4.8/pushed-gem-1.2.3.gemspec.rz", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    final byte[] inflated;
    try (var inflater = new InflaterInputStream(new ByteArrayInputStream(body))) {
      inflated = inflater.readAllBytes();
    }
    assertThat(inflated[0]).isEqualTo((byte) 0x04);
    assertThat(inflated[1]).isEqualTo((byte) 0x08);
    final var marshal = new String(inflated, StandardCharsets.ISO_8859_1);
    assertThat(marshal).contains("pushed-gem").contains("1.2.3");
  }

  @Test
  @DisplayName("GET .../quick/Marshal.4.8/<gem>.gemspec.rz of an unknown version is 404")
  void gemspecRzOfAnUnknownVersionIsNotFound() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), this.adminProtocolBearerToken())
        .andExpect(status().isOk());

    this.protocol(
            get("/{repo}/quick/Marshal.4.8/pushed-gem-9.9.9.gemspec.rz", repo.getName())
                .header(AUTHORIZATION, this.adminProtocolBearerToken()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName(
      "a gem whose name contains a hyphen immediately followed by a digit downloads by its own "
          + "filename (RPS-1236)")
  void downloadsGemWhoseNameContainsAHyphenDigit() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gemBytes = gem("x-2fa", "1.0.0");
    this.push(repo.getName(), gemBytes, this.adminProtocolBearerToken()).andExpect(status().isOk());

    final var downloaded =
        this.protocol(
                get("/{repo}/gems/x-2fa-1.0.0.gem", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(downloaded).isEqualTo(gemBytes);
  }

  @Test
  @DisplayName("a platform gem downloads by its platform-suffixed filename (RPS-1236)")
  void downloadsPlatformGem() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var gemBytes = gem("platform-gem", "1.0.0", "java", "fixture");
    this.push(repo.getName(), gemBytes, this.adminProtocolBearerToken()).andExpect(status().isOk());

    final var downloaded =
        this.protocol(
                get("/{repo}/gems/platform-gem-1.0.0-java.gem", repo.getName())
                    .header(AUTHORIZATION, this.adminProtocolBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();

    assertThat(downloaded).isEqualTo(gemBytes);
  }

  @Test
  @DisplayName(
      "a yanked version is omitted from /info but still marked '-' in /versions (RPS-1235)")
  void yankedVersionIsOmittedFromInfoButMarkedInVersions() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var token = this.adminProtocolBearerToken();
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), token).andExpect(status().isOk());
    this.push(repo.getName(), gem("pushed-gem", "2.0.0"), token).andExpect(status().isOk());

    this.protocol(
            delete("/{repo}/api/v1/gems/yank", repo.getName())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("gem_name", "pushed-gem")
                .param("version", "1.2.3"))
        .andExpect(status().isOk());

    // saveVersionsChecksum's @Modifying bulk UPDATE does not refresh the RubyGem entity already
    // cached in this test's one shared persistence context (each real request gets its own, so
    // this is a test-only artifact); clear it so the /versions read below sees the fresh checksum.
    this.entityManager.flush();
    this.entityManager.clear();

    final var info =
        this.protocol(get("/{repo}/info/pushed-gem", repo.getName()).header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(info).contains("2.0.0").doesNotContain("1.2.3");

    final var versions =
        this.protocol(get("/{repo}/versions", repo.getName()).header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(versions).contains("-1.2.3");
  }

  @Test
  @DisplayName(
      "a yanked gem's file stays downloadable, and /info forgets the version "
          + "(RPS-1235/RPS-1238)")
  void yankedGemFileIsStillDownloadable() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var token = this.adminProtocolBearerToken();
    final var gemBytes = gem("pushed-gem", "1.2.3");
    this.push(repo.getName(), gemBytes, token).andExpect(status().isOk());

    this.protocol(
            delete("/{repo}/api/v1/gems/yank", repo.getName())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("gem_name", "pushed-gem")
                .param("version", "1.2.3"))
        .andExpect(status().isOk());

    final var downloaded =
        this.protocol(
                get("/{repo}/gems/pushed-gem-1.2.3.gem", repo.getName())
                    .header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsByteArray();
    assertThat(downloaded).isEqualTo(gemBytes);

    final var info =
        this.protocol(get("/{repo}/info/pushed-gem", repo.getName()).header(AUTHORIZATION, token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(info).doesNotContain("1.2.3");
  }

  @Test
  @DisplayName("HEAD mirrors GET's status (RPS-1237)")
  void headMirrorsGetStatus() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    final var token = this.adminProtocolBearerToken();
    this.push(repo.getName(), gem("pushed-gem", "1.2.3"), token).andExpect(status().isOk());

    this.protocol(
            head("/{repo}/gems/pushed-gem-1.2.3.gem", repo.getName()).header(AUTHORIZATION, token))
        .andExpect(status().isOk());

    this.protocol(
            head("/{repo}/gems/never-published-9.9.9.gem", repo.getName())
                .header(AUTHORIZATION, token))
        .andExpect(status().isNotFound());

    this.protocol(head("/{repo}/versions", repo.getName()).header(AUTHORIZATION, token))
        .andExpect(status().isOk());

    this.protocol(
            head("/{repo}/this/path/never/existed", repo.getName()).header(AUTHORIZATION, token))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("a request without a servlet path matches no handler: 404 unknownPath")
  void requestWithoutServletPathIsUnknown() throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));

    // Same request as push(), minus the servlet path that protocolPort() fills in.
    this.mockMvc
        .perform(
            post(PUBLISH_PATH, repo.getName())
                .with(
                    request -> {
                      request.setLocalPort(PROTOCOL_PORT);
                      return request;
                    })
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem("pushed-gem", "1.2.3")))
        .andExpect(status().isNotFound());
  }

  /**
   * RPS-1071: a gemspec value longer than its {@code ruby_gem_version} column used to fail the row
   * insert with a {@code DataIntegrityViolationException} that named no field. The authors are now
   * cut and a homepage dropped; a name, version, platform or required Ruby version is rejected with
   * a 400 that names the field. All of it happens before a row or the file is written, for a new
   * version and for one that replaces an existing version alike.
   *
   * <p>None of these reaches the database with an over-long value, so they run in this class's test
   * transaction. A row the database itself rejects is in {@link RubyPublishStorageConsistencyIT}.
   */
  @Nested
  @DisplayName("over-long gemspec metadata (RPS-1071)")
  class OverLongMetadata {

    private static final String GEM = "long-metadata";

    private Repo overridableRepo() {
      final var repo = RubyGemProtocolIT.this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
      final var managed =
          RubyGemProtocolIT.this.repoRepository.findByName(repo.getName()).orElseThrow();
      managed.setAllowOverride(true);
      RubyGemProtocolIT.this.repoRepository.saveAndFlush(managed);

      return RubyGemProtocolIT.this.reloadRepo(repo.getName());
    }

    private Repo newRepo() {
      return RubyGemProtocolIT.this.seedRepo(RepoType.RUBY, uniqueRepoName("ruby"));
    }

    private ResultActions push(final Repo repo, final byte[] gem) throws Exception {
      return RubyGemProtocolIT.this.push(
          repo.getName(), gem, RubyGemProtocolIT.this.adminProtocolBearerToken());
    }

    private Map<String, Object> storedVersion(final Repo repo) {
      return RubyGemProtocolIT.this.jdbcTemplate.queryForMap(
          """
          select v.version, v.platform, v.checksum, v.authors, v.homepage, v.required_ruby_version
            from ruby_gem_version v join ruby_gem g on g.id = v.gem_id
           where g.repo_id = ? and g.name = ?
          """,
          repo.getId(),
          GEM);
    }

    private int rowCount(final Repo repo) {
      final var count =
          RubyGemProtocolIT.this.jdbcTemplate.queryForObject(
              """
              select (select count(*) from ruby_gem where repo_id = ?)
                   + (select count(*) from ruby_gem_version v join ruby_gem g on g.id = v.gem_id
                       where g.repo_id = ?)
              """,
              Integer.class,
              repo.getId(),
              repo.getId());

      return count == null ? 0 : count;
    }

    private List<String> storedGemFiles(final Repo repo) throws IOException {
      final var dir = storageDirOf(repo);
      if (!Files.exists(dir)) {
        return List.of();
      }
      try (final var files = Files.walk(dir)) {
        return files
            .filter(Files::isRegularFile)
            .map(file -> file.getFileName().toString())
            .filter(name -> name.endsWith(".gem"))
            .toList();
      }
    }

    private static List<String> authors(final int count) {
      // 10 characters each, so 42 of them and the separators between them fit the column.
      return IntStream.rangeClosed(1, count).mapToObj("author-%03d"::formatted).toList();
    }

    /** A gem whose one length-limited field is one character over its column. */
    private static byte[] overLongGem(final String column) throws IOException {
      final var name = "name".equals(column) ? "n".repeat(256) : GEM;
      final var version = "version".equals(column) ? "1." + "0".repeat(63) : "1.0.0";
      final var platform = "platform".equals(column) ? "p".repeat(65) : "ruby";
      final var ruby = "required_ruby_version".equals(column) ? "1".repeat(62) : "3.1.0";

      return gem(
          name, version, platform, "fixture", List.of("Alice"), "https://example.test", ruby);
    }

    @Test
    @DisplayName("stores every field that fits its column, including a value exactly at the limit")
    void storesValuesAtTheLimit() throws Exception {
      final var repo = this.newRepo();
      final var version = "1." + "0".repeat(GemspecParser.MAX_VERSION_LENGTH - 2);
      final var platform = "p".repeat(GemspecParser.MAX_PLATFORM_LENGTH);
      final var authors = "a".repeat(GemspecParser.MAX_AUTHORS_LENGTH);
      final var homepage = "h".repeat(GemspecParser.MAX_HOMEPAGE_LENGTH);
      final var ruby = "1".repeat(GemspecParser.MAX_REQUIRED_RUBY_VERSION_LENGTH - 3);

      this.push(repo, gem(GEM, version, platform, "fixture", List.of(authors), homepage, ruby))
          .andExpect(status().isOk());

      assertThat(this.storedVersion(repo))
          .containsEntry("version", version)
          .containsEntry("platform", platform)
          .containsEntry("authors", authors)
          .containsEntry("homepage", homepage)
          .containsEntry("required_ruby_version", ">= " + ruby);
    }

    @Test
    @DisplayName("cuts over-long authors after the last author that fits and accepts the push")
    void cutsAuthorsOfANewVersion() throws Exception {
      final var repo = this.newRepo();

      this.push(
              repo,
              gem(GEM, "1.0.0", "ruby", "fixture", authors(60), "https://example.test", "3.1.0"))
          .andExpect(status().isOk());

      final var stored = (String) this.storedVersion(repo).get("authors");
      assertThat(stored).isEqualTo(String.join(", ", authors(42)));
      assertThat(stored).hasSizeLessThanOrEqualTo(GemspecParser.MAX_AUTHORS_LENGTH);
    }

    @Test
    @DisplayName("drops an over-long homepage and accepts the push")
    void dropsHomepageOfANewVersion() throws Exception {
      final var repo = this.newRepo();
      final var homepage = "https://example.test/" + "h".repeat(GemspecParser.MAX_HOMEPAGE_LENGTH);

      this.push(repo, gem(GEM, "1.0.0", "ruby", "fixture", List.of("Alice"), homepage, "3.1.0"))
          .andExpect(status().isOk());

      assertThat(this.storedVersion(repo)).containsEntry("homepage", null);
    }

    @Test
    @DisplayName("cuts the authors and drops the homepage of a version that replaces another")
    void cutsAndDropsOnOverride() throws Exception {
      final var repo = this.overridableRepo();
      this.push(repo, gem(GEM, "1.0.0", "ruby", "original")).andExpect(status().isOk());
      final var originalChecksum = this.storedVersion(repo).get("checksum");
      final var homepage = "https://example.test/" + "h".repeat(GemspecParser.MAX_HOMEPAGE_LENGTH);

      this.push(repo, gem(GEM, "1.0.0", "ruby", "second", authors(60), homepage, "3.1.0"))
          .andExpect(status().isOk());

      assertThat(this.storedVersion(repo))
          .containsEntry("authors", String.join(", ", authors(42)))
          .containsEntry("homepage", null)
          .doesNotContainEntry("checksum", originalChecksum);
      assertThat(this.rowCount(repo)).as("the version is replaced, not added").isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            name                  | gemNameTooLong                | The gem name is longer than 255 characters.
            version               | gemVersionTooLong             | The gem version is longer than 64 characters.
            platform              | gemPlatformTooLong            | The gem platform is longer than 64 characters.
            required_ruby_version | gemRequiredRubyVersionTooLong | The required_ruby_version of the gem is longer than 64 characters.
            """)
    @DisplayName("rejects an over-long field with a 400 that names it and stores nothing")
    void rejectsOverLongFieldOfANewVersion(
        final String column, final String msgId, final String text) throws Exception {
      final var repo = this.newRepo();

      this.push(repo, overLongGem(column))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value(msgId))
          .andExpect(jsonPath("$.text").value(text));

      assertThat(this.rowCount(repo)).as("no gem or version row").isZero();
      assertThat(this.storedGemFiles(repo)).as("no .gem file").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "name,gemNameTooLong",
      "version,gemVersionTooLong",
      "platform,gemPlatformTooLong",
      "required_ruby_version,gemRequiredRubyVersionTooLong"
    })
    @DisplayName(
        "rejects an over-long field of a push to an overridable repo and keeps the version")
    void rejectsOverLongFieldOnOverride(final String column, final String msgId) throws Exception {
      final var repo = this.overridableRepo();
      final var original = gem(GEM, "1.0.0", "ruby", "original");
      this.push(repo, original).andExpect(status().isOk());
      final var before = this.storedVersion(repo);
      final var rows = this.rowCount(repo);

      this.push(repo, overLongGem(column))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value(msgId));

      assertThat(this.storedVersion(repo)).as("the stored version is untouched").isEqualTo(before);
      assertThat(this.rowCount(repo)).isEqualTo(rows);
      assertThat(this.storedGemFiles(repo)).containsExactly(GEM + "-1.0.0.gem");
      assertThat(storageDirOf(repo).resolve("gems").resolve(GEM).resolve(GEM + "-1.0.0.gem"))
          .hasBinaryContent(original);
    }
  }
}
