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
package io.repsy.os.server.protocols.npm.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_ENCODING;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.security.scan.dtos.FixStatus;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.dtos.Severity;
import io.repsy.os.server.security.scan.entities.VulnerabilityFinding;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityFindingRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1329: {@code npm audit} and the audits of pnpm, yarn and bun used to end in {@code 404
 * unknownPath}. The findings are seeded through the repositories, as a finished scan would leave
 * them, and every request goes through the real router, the way each client sends it: plain JSON
 * (pnpm, yarn) or gzip (npm, bun) to the bulk endpoint, and the legacy tree to {@code /audits} and
 * {@code /audits/quick}.
 */
@DisplayName("npm wire protocol audit endpoints")
class NpmAuditProtocolIT extends AbstractIntegrationTest {

  private static final String BULK = "/{repo}/-/npm/v1/security/advisories/bulk";
  private static final String AUDITS = "/{repo}/-/npm/v1/security/audits";
  private static final String QUICK = "/{repo}/-/npm/v1/security/audits/quick";
  private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
  private static final String LODASH = "lodash";

  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityFindingRepository findingRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  /**
   * A repo whose security scan setting is on, because a repo that is not scanned reports nothing.
   */
  private Repo repo(final boolean privateRepo) {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("audit"), privateRepo, null);

    return this.setSecurityScan(repo, true);
  }

  private Repo setSecurityScan(final Repo repo, final boolean enabled) {
    final var stored = this.repoRepository.findById(repo.getId()).orElseThrow();
    stored.setSecurityScanEnabled(enabled);
    this.repoRepository.saveAndFlush(stored);

    return this.reloadRepo(repo.getName());
  }

  private static byte[] gzip(final String text) throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (var out = new GZIPOutputStream(bytes)) {
      out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    return bytes.toByteArray();
  }

  private MockHttpServletResponse plain(final String path, final Repo repo, final String body)
      throws Exception {
    return this.protocol(
        post(path, repo.getName())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body.getBytes(StandardCharsets.UTF_8)));
  }

  private MockHttpServletResponse gzipped(final String path, final Repo repo, final String body)
      throws Exception {
    return this.protocol(
        post(path, repo.getName())
            .header(CONTENT_ENCODING, "gzip")
            .contentType(MediaType.APPLICATION_JSON)
            .content(gzip(body)));
  }

  /**
   * Seeds a scan of {@code artifact@version}; its createdAt orders it among the same artifact's.
   */
  private VulnerabilityScan seedScan(
      final Repo repo,
      final String artifact,
      final String version,
      final ScanStatus status,
      final Instant createdAt) {

    final var scan = new VulnerabilityScan();
    scan.setRepo(repo);
    scan.setArtifactName(artifact);
    scan.setArtifactVersion(version);
    scan.setStatus(status);
    scan.setScannerName("trivy");
    scan.setScannerVersion("0.58.1");
    scan.setStartedAt(createdAt.plusSeconds(1));
    scan.setCompletedAt(status == ScanStatus.COMPLETED ? createdAt.plusSeconds(30) : null);
    final var saved = this.scanRepository.saveAndFlush(scan);

    // The creation timestamp is generated on insert, so the order of two scans is set afterwards.
    this.entityManager
        .createQuery("update VulnerabilityScan s set s.createdAt = :createdAt where s.id = :id")
        .setParameter("createdAt", createdAt)
        .setParameter("id", saved.getId())
        .executeUpdate();

    return saved;
  }

  private VulnerabilityScan completedScan(
      final Repo repo, final String artifact, final Instant at) {
    return this.seedScan(repo, artifact, "1.0.0", ScanStatus.COMPLETED, at);
  }

  private void seedFinding(
      final VulnerabilityScan scan,
      final String cve,
      final Severity severity,
      final String packageName,
      final String packageVersion,
      final String fixedVersion,
      final FixStatus fixStatus) {

    final var finding = new VulnerabilityFinding();
    finding.setScan(scan);
    finding.setCveId(cve);
    finding.setSeverity(severity);
    finding.setPackageName(packageName);
    finding.setPackageVersion(packageVersion);
    finding.setFixedVersion(fixedVersion);
    finding.setDescription("Lodash versions prior to 4.17.21 are vulnerable\nMore text");
    finding.setReferenceUrl(
        "https://avd.aquasec.com/nvd/" + cve.toLowerCase(java.util.Locale.ROOT));
    finding.setFixStatus(fixStatus);
    finding.setCvssScore(7.2);
    finding.setCvssVector("CVSS:3.1/AV:N/AC:L/PR:H/UI:N/S:U/C:H/I:H/A:H");
    this.findingRepository.saveAndFlush(finding);
  }

  private void seedLodash(final Repo repo) {
    this.seedFinding(
        this.completedScan(repo, "app", T0),
        "CVE-2021-23337",
        Severity.HIGH,
        LODASH,
        "4.17.20",
        "4.17.21",
        FixStatus.FIXED);
  }

  @Test
  @DisplayName("bulk answers the advisory of a requested vulnerable version, plain or gzip")
  void bulkPlainAndGzip() throws Exception {
    final var repo = this.repo(false);
    this.seedLodash(repo);
    final var body = "{\"lodash\":[\"4.17.20\",\"4.17.21\"],\"left-pad\":[\"1.3.0\"]}";

    final var plain = this.plain(BULK, repo, body);
    final var zipped = this.gzipped(BULK, repo, body);

    for (final var response : List.of(plain, zipped)) {
      final var json = response.getContentAsString();

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
      assertThat(JsonPath.<Map<String, Object>>read(json, "$")).containsOnlyKeys(LODASH);
      assertThat(JsonPath.<List<Object>>read(json, "$.lodash")).hasSize(1);
      assertThat(JsonPath.<Long>read(json, "$.lodash[0].id")).isPositive().isLessThan(1L << 53);
      assertThat(JsonPath.<String>read(json, "$.lodash[0].url"))
          .isEqualTo("https://avd.aquasec.com/nvd/cve-2021-23337");
      assertThat(JsonPath.<String>read(json, "$.lodash[0].title"))
          .isEqualTo("CVE-2021-23337: Lodash versions prior to 4.17.21 are vulnerable");
      assertThat(JsonPath.<String>read(json, "$.lodash[0].severity")).isEqualTo("high");
      assertThat(JsonPath.<String>read(json, "$.lodash[0].vulnerable_versions"))
          .isEqualTo("4.17.20");
      assertThat(JsonPath.<List<Object>>read(json, "$.lodash[0].cwe")).isEmpty();
      assertThat(JsonPath.<Double>read(json, "$.lodash[0].cvss.score")).isEqualTo(7.2);
      assertThat(JsonPath.<String>read(json, "$.lodash[0].cvss.vectorString"))
          .startsWith("CVSS:3.1/AV:N");
    }
    assertThat(plain.getContentAsString()).isEqualTo(zipped.getContentAsString());
  }

  @Test
  @DisplayName(
      "bulk answers {} for a version that is not vulnerable, an unknown package and no body")
  void bulkNothingKnown() throws Exception {
    final var repo = this.repo(false);
    this.seedLodash(repo);

    assertThat(this.plain(BULK, repo, "{\"lodash\":[\"4.17.21\"]}").getContentAsString())
        .isEqualTo("{}");
    assertThat(this.plain(BULK, repo, "{\"nothing\":[\"1.0.0\"]}").getContentAsString())
        .isEqualTo("{}");
    assertThat(this.plain(BULK, repo, "").getContentAsString()).isEqualTo("{}");
    assertThat(
            this.plain(BULK, this.repo(false), "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isEqualTo("{}");
  }

  @Test
  @DisplayName("bulk reports only the findings of the newest completed scan of an artifact version")
  void newestCompletedScanOnly() throws Exception {
    final var repo = this.repo(false);
    this.seedFinding(
        this.completedScan(repo, "app", T0),
        "CVE-OLD",
        Severity.LOW,
        LODASH,
        "4.17.20",
        null,
        FixStatus.AFFECTED);
    this.seedFinding(
        this.completedScan(repo, "app", T0.plusSeconds(3600)),
        "CVE-NEW",
        Severity.CRITICAL,
        LODASH,
        "4.17.20",
        "4.17.21",
        FixStatus.FIXED);
    // Scans that did not finish never replace a finished one, and never report anything.
    this.seedFinding(
        this.seedScan(repo, "app", "1.0.0", ScanStatus.RUNNING, T0.plusSeconds(7200)),
        "CVE-RUNNING",
        Severity.HIGH,
        LODASH,
        "4.17.20",
        null,
        FixStatus.AFFECTED);
    this.seedFinding(
        this.seedScan(repo, "app", "1.0.0", ScanStatus.FAILED, T0.plusSeconds(10800)),
        "CVE-FAILED",
        Severity.HIGH,
        LODASH,
        "4.17.20",
        null,
        FixStatus.AFFECTED);

    final var json = this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}").getContentAsString();

    assertThat(JsonPath.<List<Object>>read(json, "$.lodash")).hasSize(1);
    assertThat(JsonPath.<String>read(json, "$.lodash[0].title")).startsWith("CVE-NEW");
    assertThat(JsonPath.<String>read(json, "$.lodash[0].severity")).isEqualTo("critical");
  }

  @Test
  @DisplayName("bulk leaves out a finding that says the version is not affected")
  void notAffectedIsLeftOut() throws Exception {
    final var repo = this.repo(false);
    final var scan = this.completedScan(repo, "app", T0);
    this.seedFinding(scan, "CVE-1", Severity.HIGH, LODASH, "4.17.20", null, FixStatus.NOT_AFFECTED);

    assertThat(this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isEqualTo("{}");
  }

  @Test
  @DisplayName("bulk never returns the findings of another repo")
  void otherReposAreExcluded() throws Exception {
    final var repo = this.repo(false);
    final var other = this.repo(false);
    this.seedLodash(other);

    assertThat(this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isEqualTo("{}");
    assertThat(this.plain(BULK, other, "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isNotEqualTo("{}");
  }

  @Test
  @DisplayName(
      "bulk maps an unknown severity to low and merges a vulnerability found in many versions")
  void severityAndVersions() throws Exception {
    final var repo = this.repo(false);
    final var scan = this.completedScan(repo, "app", T0);
    this.seedFinding(
        scan, "GHSA-abcd", Severity.UNKNOWN, LODASH, "4.17.19", "4.17.21, 5.0.1", FixStatus.FIXED);
    this.seedFinding(
        scan, "GHSA-abcd", Severity.MEDIUM, LODASH, "4.17.20", "4.17.21", FixStatus.FIXED);
    this.seedFinding(
        this.completedScan(repo, "other-app", T0),
        "CVE-2",
        Severity.UNKNOWN,
        "left-pad",
        "1.0.0",
        null,
        FixStatus.AFFECTED);

    final var json =
        this.plain(BULK, repo, "{\"lodash\":[\"4.17.19\",\"4.17.20\"],\"left-pad\":[\"1.0.0\"]}")
            .getContentAsString();

    assertThat(JsonPath.<String>read(json, "$.lodash[0].severity")).isEqualTo("moderate");
    assertThat(JsonPath.<String>read(json, "$.lodash[0].vulnerable_versions"))
        .isEqualTo("4.17.19 || 4.17.20");
    assertThat(JsonPath.<String>read(json, "$.lodash[0].url"))
        .isEqualTo("https://avd.aquasec.com/nvd/ghsa-abcd");
    assertThat(JsonPath.<String>read(json, "$.left-pad[0].severity")).isEqualTo("low");
  }

  @Test
  @DisplayName("legacy answers the report for a pnpm tree, plain, on /audits")
  void legacyAuditsPnpmTree() throws Exception {
    final var repo = this.repo(false);
    this.seedLodash(repo);
    final var tree =
        """
        {"name":"root","version":"undefined","dependencies":{
           ".":{"dependencies":{"express":{"version":"4.0.0","dependencies":{"lodash":{"version":"4.17.20"}}}}},
           "packages__web":{"version":"undefined","dependencies":{"lodash":{"version":"4.17.21"}}}},
         "dev":false,"install":[],"remove":[],"metadata":{},"requires":{}}
        """;

    final var response = this.plain(AUDITS, repo, tree);
    final var json = response.getContentAsString();
    final var id =
        JsonPath.<Long>read(
            this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}").getContentAsString(),
            "$.lodash[0].id");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<Map<String, Object>>read(json, "$.advisories"))
        .containsOnlyKeys(id.toString());
    final var advisory = "$.advisories." + id;
    assertThat(JsonPath.<String>read(json, advisory + ".module_name")).isEqualTo(LODASH);
    assertThat(JsonPath.<String>read(json, advisory + ".severity")).isEqualTo("high");
    assertThat(JsonPath.<String>read(json, advisory + ".vulnerable_versions")).isEqualTo("4.17.20");
    assertThat(JsonPath.<String>read(json, advisory + ".patched_versions")).isEqualTo(">=4.17.21");
    assertThat(JsonPath.<List<String>>read(json, advisory + ".cves"))
        .containsExactly("CVE-2021-23337");
    assertThat(JsonPath.<String>read(json, advisory + ".found_by.name")).isEqualTo("Repsy");
    assertThat(JsonPath.<String>read(json, advisory + ".reported_by.name")).isEqualTo("trivy");
    assertThat(JsonPath.<List<String>>read(json, advisory + ".findings[0].paths"))
        .containsExactly(".>express>lodash");
    assertThat(JsonPath.<String>read(json, advisory + ".findings[0].version")).isEqualTo("4.17.20");
    assertThat(JsonPath.<String>read(json, "$.actions[0].module")).isEqualTo(LODASH);
    assertThat(JsonPath.<String>read(json, "$.actions[0].resolves[0].path"))
        .isEqualTo(".>express>lodash");
    assertThat(JsonPath.<Integer>read(json, "$.metadata.vulnerabilities.high")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(json, "$.metadata.vulnerabilities.info")).isZero();
    assertThat(JsonPath.<Integer>read(json, "$.metadata.totalDependencies")).isEqualTo(4);
  }

  @Test
  @DisplayName("legacy answers the report for an npm 6 tree, gzip, on /audits/quick")
  void legacyQuickNpmTree() throws Exception {
    final var repo = this.repo(false);
    this.seedLodash(repo);
    final var tree =
        """
        {"name":"app","version":"1.0.0","requires":{"lodash":"^4.17.0"},"dependencies":{
           "lodash":{"version":"4.17.20"},
           "jest":{"version":"27.0.0","dev":true,"dependencies":{"lodash":{"version":"4.17.20","dev":true}}}},
         "install":[],"remove":[],"metadata":{"npm_version":"6.14.0"}}
        """;

    final var response = this.gzipped(QUICK, repo, tree);
    final var json = response.getContentAsString();

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(json, "$.advisories.*.findings[0].paths[*]"))
        .containsExactlyInAnyOrder("lodash", "jest>lodash");
    // One of the two places of the vulnerable version is not a dev dependency.
    assertThat(JsonPath.<List<Boolean>>read(json, "$.advisories.*.findings[0].dev"))
        .containsExactly(false);
    assertThat(JsonPath.<Integer>read(json, "$.metadata.dependencies")).isEqualTo(1);
    assertThat(JsonPath.<Integer>read(json, "$.metadata.devDependencies")).isEqualTo(2);
    assertThat(JsonPath.<Integer>read(json, "$.metadata.totalDependencies")).isEqualTo(3);
  }

  @Test
  @DisplayName("legacy with nothing known answers an empty report that has its metadata")
  void legacyNothingKnown() throws Exception {
    final var repo = this.repo(false);

    final var json =
        this.plain(AUDITS, repo, "{\"dependencies\":{\"a\":{\"version\":\"1.0.0\"}}}")
            .getContentAsString();

    assertThat(JsonPath.<Map<String, Object>>read(json, "$.advisories")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(json, "$.actions")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(json, "$.muted")).isEmpty();
    assertThat(JsonPath.<Map<String, Integer>>read(json, "$.metadata.vulnerabilities"))
        .containsOnlyKeys("info", "low", "moderate", "high", "critical")
        .containsValues(0);
    assertThat(JsonPath.<Integer>read(json, "$.metadata.totalDependencies")).isEqualTo(1);
    assertThat(this.plain(QUICK, repo, "").getStatus()).isEqualTo(200);
  }

  @Test
  @DisplayName("answers 400 for a body that is not a JSON object, and for broken gzip")
  void badBodies() throws Exception {
    final var repo = this.repo(false);

    for (final var path : List.of(BULK, AUDITS, QUICK)) {
      final var notJson = this.plain(path, repo, "{not json");
      final var array = this.plain(path, repo, "[1]");

      assertThat(notJson.getStatus()).isEqualTo(400);
      assertThat(notJson.getContentAsString())
          .isEqualTo("{\"error\":\"invalid audit request body\"}");
      assertThat(array.getStatus()).isEqualTo(400);
    }

    final var brokenGzip =
        this.protocol(
            post(BULK, repo.getName())
                .header(CONTENT_ENCODING, "gzip")
                .content("not gzip".getBytes(StandardCharsets.UTF_8)));

    assertThat(brokenGzip.getStatus()).isEqualTo(400);
  }

  @Test
  @DisplayName("of a private repo needs credentials, and answers the report with them")
  void privateRepo() throws Exception {
    final var repo = this.repo(true);
    this.seedLodash(repo);
    final var token =
        this.protocolBearerTokenFor(createUser(uniqueUsername("audit"), UserRole.USER));

    final var anonymous = this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}");
    final var authenticated =
        this.protocol(
            post(BULK, repo.getName())
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lodash\":[\"4.17.20\"]}".getBytes(StandardCharsets.UTF_8)));

    assertThat(anonymous.getStatus()).isEqualTo(401);
    assertThat(anonymous.getHeader(WWW_AUTHENTICATE)).startsWith("Basic");
    assertThat(this.plain(AUDITS, repo, "{}").getStatus()).isEqualTo(401);
    assertThat(authenticated.getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<Object>>read(authenticated.getContentAsString(), "$.lodash"))
        .hasSize(1);
  }

  @Test
  @DisplayName("only POST reaches the audit endpoints")
  void onlyPost() throws Exception {
    final var repo = this.repo(false);

    assertThat(this.protocol(get(BULK, repo.getName())).getStatus()).isEqualTo(404);
    assertThat(this.protocol(get(AUDITS, repo.getName())).getStatus()).isEqualTo(404);
  }

  /** One vulnerability found in 1.0.0 (fixed in 1.0.5) and in 2.0.0 (fixed in 2.0.3). */
  private Repo repoWithTwoVersionsOfOneVulnerability() {
    final var repo = this.repo(false);
    final var scan = this.completedScan(repo, "app", T0);
    this.seedFinding(scan, "CVE-9", Severity.CRITICAL, "pkg", "1.0.0", "1.0.5", FixStatus.FIXED);
    this.seedFinding(scan, "CVE-9", Severity.LOW, "pkg", "2.0.0", "2.0.3", FixStatus.FIXED);

    return repo;
  }

  @Test
  @DisplayName("bulk reports only the requested version: 2.0.0 alone is patched in >=2.0.3")
  void bulkRestrictsToTheRequestedVersions() throws Exception {
    final var repo = this.repoWithTwoVersionsOfOneVulnerability();

    final var only2 = this.plain(BULK, repo, "{\"pkg\":[\"2.0.0\"]}").getContentAsString();
    final var both = this.plain(BULK, repo, "{\"pkg\":[\"1.0.0\",\"2.0.0\"]}").getContentAsString();

    assertThat(JsonPath.<String>read(only2, "$.pkg[0].vulnerable_versions")).isEqualTo("2.0.0");
    assertThat(JsonPath.<String>read(only2, "$.pkg[0].severity")).isEqualTo("low");
    assertThat(JsonPath.<String>read(both, "$.pkg[0].vulnerable_versions"))
        .isEqualTo("1.0.0 || 2.0.0");
    assertThat(JsonPath.<String>read(both, "$.pkg[0].severity")).isEqualTo("critical");
  }

  @Test
  @DisplayName("legacy reports only the requested version: 2.0.0 alone is patched in >=2.0.3")
  void legacyRestrictsToTheRequestedVersions() throws Exception {
    final var repo = this.repoWithTwoVersionsOfOneVulnerability();

    final var json =
        this.plain(AUDITS, repo, "{\"dependencies\":{\"pkg\":{\"version\":\"2.0.0\"}}}")
            .getContentAsString();
    final var advisories = JsonPath.<Map<String, Object>>read(json, "$.advisories");
    final var id = advisories.keySet().iterator().next();

    assertThat(advisories).hasSize(1);
    assertThat(JsonPath.<String>read(json, "$.advisories." + id + ".vulnerable_versions"))
        .isEqualTo("2.0.0");
    assertThat(JsonPath.<String>read(json, "$.advisories." + id + ".patched_versions"))
        .isEqualTo(">=2.0.3");
    assertThat(JsonPath.<List<String>>read(json, "$.advisories." + id + ".findings[*].version"))
        .containsExactly("2.0.0");
  }

  @Test
  @DisplayName("a repo whose security scan is off reports nothing, though it has old findings")
  void scanOffReportsNothing() throws Exception {
    final var repo = this.setSecurityScan(this.repo(false), false);
    this.seedLodash(repo);

    assertThat(this.plain(BULK, repo, "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isEqualTo("{}");
    final var legacy =
        this.plain(AUDITS, repo, "{\"dependencies\":{\"lodash\":{\"version\":\"4.17.20\"}}}")
            .getContentAsString();
    assertThat(JsonPath.<Map<String, Object>>read(legacy, "$.advisories")).isEmpty();

    final var on = this.setSecurityScan(repo, true);
    assertThat(this.plain(BULK, on, "{\"lodash\":[\"4.17.20\"]}").getContentAsString())
        .isNotEqualTo("{}");
  }

  @Test
  @DisplayName("answers 413 for a gzip bomb, and 400 for a very deep body or too many names")
  void boundsTheRequest() throws Exception {
    final var repo = this.repo(false);
    final var bomb = "{\"a\":[\"" + "0".repeat(9 * 1024 * 1024) + "\"]}";
    final var deep = "{\"a\":" + "[".repeat(1000) + "]".repeat(1000) + "}";
    final var names = new StringBuilder("{");
    for (var i = 0; i < 20_001; i++) {
      names.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":[\"1.0.0\"]");
    }
    names.append('}');

    final var bombResponse = this.gzipped(BULK, repo, bomb);

    assertThat(bombResponse.getStatus()).isEqualTo(413);
    assertThat(bombResponse.getContentAsString())
        .isEqualTo("{\"error\":\"audit request body too large\"}");
    assertThat(this.gzipped(BULK, repo, deep).getStatus()).isEqualTo(400);
    assertThat(this.gzipped(BULK, repo, names.toString()).getStatus()).isEqualTo(400);
    assertThat(this.gzipped(AUDITS, repo, deep).getStatus()).isEqualTo(400);
  }
}
