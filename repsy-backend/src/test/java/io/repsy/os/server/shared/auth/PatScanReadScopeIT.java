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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.scanner.dtos.FixStatus;
import io.repsy.libs.scanner.dtos.Severity;
import io.repsy.os.server.security.scan.dtos.ScanStatus;
import io.repsy.os.server.security.scan.entities.VulnerabilityFinding;
import io.repsy.os.server.security.scan.entities.VulnerabilityScan;
import io.repsy.os.server.security.scan.repositories.VulnerabilityFindingRepository;
import io.repsy.os.server.security.scan.repositories.VulnerabilityScanRepository;
import io.repsy.os.server.security.scan.utils.LatestScanMarkerUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * RPS-2260: the scan routes accept {@code repo:read} or {@code scan:read}. This is the scope matrix
 * (repo:read, scan:read, both, neither) against every route of {@code VulnerabilityScanController},
 * the reads and the two that start a scan, plus what {@code scan:read} must not open: the repo and
 * its contents, and the admin-only cross-repo scan browser.
 *
 * <p>A route that lets the token in is only asserted to be neither {@code 401} nor {@code 403}: the
 * repo has no scans, so what is left (an empty page, a {@code 404} for an unknown scan) is not what
 * this class is about.
 */
@DisplayName("scan:read on the scan routes")
class PatScanReadScopeIT extends AbstractPatIT {

  private static final String ARTIFACT = "/artifacts/pkg";
  private static final String SCOPED = "/scopes/team/artifacts/pkg";
  private static final String VERSION = "/versions/1.0.0";

  private static final String CVE = "CVE-2026-0001";

  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityFindingRepository findingRepository;

  private User user;
  private Repo repo;
  private UUID scanId;

  @BeforeEach
  void setUp() {
    this.user = this.createUser(uniqueUsername("patscan"), UserRole.USER);
    this.repo = this.privateRepo(RepoType.NPM);
    this.scanId = this.seedCompletedScan(this.repo, "pkg", "1.0.0");
  }

  /** A completed scan of {@code name@version} with one critical finding. */
  private UUID seedCompletedScan(final Repo target, final String name, final String version) {
    final var scan = new VulnerabilityScan();
    scan.setRepo(target);
    scan.setArtifactName(name);
    scan.setArtifactVersion(version);
    scan.setStatus(ScanStatus.COMPLETED);
    scan.setHighestSeverity(Severity.CRITICAL);
    scan.setScannerName("trivy");
    scan.setScannerVersion("0.58.1");
    scan.setStartedAt(Instant.now().minusSeconds(30));
    scan.setCompletedAt(Instant.now());

    final var id = this.scanRepository.saveAndFlush(scan).getId();
    final var finding = new VulnerabilityFinding();

    finding.setScan(this.scanRepository.getReferenceById(id));
    finding.setCveId(CVE);
    finding.setSeverity(Severity.CRITICAL);
    finding.setPackageName("openssl");
    finding.setPackageVersion("1.0");
    finding.setFixStatus(FixStatus.UNKNOWN);
    this.findingRepository.saveAndFlush(finding);
    LatestScanMarkerUtils.refreshRepoOf(this.jdbcTemplate, id);

    return id;
  }

  /** The routes of the controller that read scan results, below {@code /api/repos/{repo}}. */
  static Stream<String> readRoutes() {
    return Stream.of(
        ARTIFACT + VERSION + "/scans",
        SCOPED + VERSION + "/scans",
        ARTIFACT + "/security-summary",
        SCOPED + "/security-summary",
        "/artifacts/security-summary",
        ARTIFACT + VERSION + "/scan-overview",
        SCOPED + VERSION + "/scan-overview",
        "/scans/{scanId}",
        "/scans/{scanId}/findings",
        "/security-detail",
        ARTIFACT + "/security-detail",
        SCOPED + "/security-detail");
  }

  /** The routes that start a scan. */
  static Stream<String> startRoutes() {
    return Stream.of(ARTIFACT + VERSION + "/scan", SCOPED + VERSION + "/scan");
  }

  static Stream<Arguments> readMatrix() {
    return readRoutes()
        .flatMap(
            route ->
                Stream.of(
                    Arguments.of(route, new TokenScope[] {TokenScope.REPO_READ}, true),
                    Arguments.of(route, new TokenScope[] {TokenScope.SCAN_READ}, true),
                    Arguments.of(
                        route, new TokenScope[] {TokenScope.REPO_READ, TokenScope.SCAN_READ}, true),
                    Arguments.of(route, new TokenScope[] {TokenScope.REPO_WRITE}, true),
                    Arguments.of(route, new TokenScope[] {}, false)));
  }

  private String path(final String route) {
    return "/api/repos/%s%s"
        .formatted(this.repo.getName(), route.replace("{scanId}", this.scanId.toString()));
  }

  private String bodyOf(final String route, final Pat pat) throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get(this.path(route))
                    .with(apiPort())
                    .header(AUTHORIZATION, "Bearer " + pat.secret()))
            .andReturn()
            .getResponse();

    assertThat(response.getStatus()).as(route).isEqualTo(200);

    return response.getContentAsString();
  }

  private int statusOf(
      final org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?>
          request,
      final Pat pat)
      throws Exception {
    return this.mockMvc
        .perform(request.with(apiPort()).header(AUTHORIZATION, "Bearer " + pat.secret()))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @ParameterizedTest(name = "GET {0} with {1}: allowed={2}")
  @MethodSource("readMatrix")
  @DisplayName("a scan read route takes repo:read or scan:read and refuses a token with neither")
  void readRoutes(final String route, final TokenScope[] scopes, final boolean allowed)
      throws Exception {
    final var pat = this.seedPat(this.user, scopes);
    final var status = this.statusOf(get(this.path(route)), pat);

    if (allowed) {
      assertThat(status).isNotIn(401, 403);
    } else {
      assertThat(status).isEqualTo(403);
    }
  }

  static Stream<Arguments> scopesThatReadScans() {
    return Stream.of(
        Arguments.of((Object) new TokenScope[] {TokenScope.REPO_READ}),
        Arguments.of((Object) new TokenScope[] {TokenScope.SCAN_READ}),
        Arguments.of((Object) new TokenScope[] {TokenScope.REPO_READ, TokenScope.SCAN_READ}));
  }

  @ParameterizedTest(name = "the scan results come back with 200 for {0}")
  @MethodSource("scopesThatReadScans")
  @DisplayName("a token that may read scans gets the seeded scan back, with its findings")
  void scanResultsAreReturned(final TokenScope[] scopes) throws Exception {
    final var pat = this.seedPat(this.user, scopes);
    final var id = this.scanId.toString();

    final var list = this.bodyOf("/artifacts/pkg/versions/1.0.0/scans", pat);
    assertThat((String) JsonPath.read(list, "$.content[0].id")).isEqualTo(id);
    assertThat((String) JsonPath.read(list, "$.content[0].status")).isEqualTo("COMPLETED");

    assertThat((String) JsonPath.read(this.bodyOf("/scans/{scanId}", pat), "$.id")).isEqualTo(id);

    final var findings = this.bodyOf("/scans/{scanId}/findings", pat);
    assertThat((String) JsonPath.read(findings, "$.content[0].cveId")).isEqualTo(CVE);

    assertThat(this.bodyOf("/artifacts/pkg/versions/1.0.0/scan-overview", pat)).contains(id);
    assertThat((Integer) JsonPath.read(this.bodyOf("/security-detail", pat), "$.criticalCount"))
        .isEqualTo(1);
    assertThat(
            (Integer)
                JsonPath.read(
                    this.bodyOf("/artifacts/pkg/security-detail", pat), "$.criticalCount"))
        .isEqualTo(1);
    assertThat(this.bodyOf("/artifacts/pkg/security-summary", pat)).contains("1.0.0");
    assertThat(this.bodyOf("/artifacts/security-summary", pat)).contains("pkg");
  }

  @Test
  @DisplayName(
      "a token with only scan:read is refused the repo on the same repo that it reads scans of")
  void scanReadOnlyReadsScansOfThisRepo() throws Exception {
    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);

    this.bodyOf("/security-detail", scanOnly);

    assertThat(this.statusOf(get("/api/repos"), scanOnly)).isEqualTo(403);
    assertThat(this.statusOf(get("/api/repos/{repo}", this.repo.getName()), scanOnly))
        .isEqualTo(403);
  }

  @Test
  @DisplayName("the scan id of another repo is not readable through this one, with either scope")
  void aScanOfAnotherRepoIsNotFound() throws Exception {
    final var other = this.privateRepo(RepoType.NPM);
    final var otherScan = this.seedCompletedScan(other, "pkg", "1.0.0");
    final var path = "/api/repos/%s/scans/%s".formatted(this.repo.getName(), otherScan);

    assertThat(this.statusOf(get(path), this.seedPat(this.user, TokenScope.SCAN_READ)))
        .isEqualTo(404);
    assertThat(this.statusOf(get(path), this.seedPat(this.user, TokenScope.REPO_READ)))
        .isEqualTo(404);
  }

  @Test
  @DisplayName(
      "on the protocol port a token with only scan:read reads nothing, as a Basic password or a Bearer")
  void scanReadIsNothingOnTheWire() throws Exception {
    final var maven = this.privateRepo(RepoType.MAVEN);
    final var path = "/{repo}/com/example/lib/1.0/lib-1.0.pom";
    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);
    final var reader = this.seedPat(this.user, TokenScope.REPO_READ);

    assertThat(
            this.status(
                get(path, maven.getName())
                    .header(AUTHORIZATION, basicAuth("any", scanOnly.secret()))))
        .isEqualTo(401);
    assertThat(
            this.status(
                get(path, maven.getName()).header(AUTHORIZATION, "Bearer " + scanOnly.secret())))
        .isEqualTo(401);

    // the same requests with repo:read get past authentication, so the 401 above is the scope
    assertThat(
            this.status(
                get(path, maven.getName())
                    .header(AUTHORIZATION, basicAuth("any", reader.secret()))))
        .isEqualTo(404);
    assertThat(
            this.status(
                get(path, maven.getName()).header(AUTHORIZATION, "Bearer " + reader.secret())))
        .isEqualTo(404);
  }

  @ParameterizedTest(name = "GET {0} without a token is 401")
  @MethodSource("readRoutes")
  @DisplayName("a scan read route of a private repo needs a credential")
  void noToken(final String route) throws Exception {
    final var status =
        this.mockMvc
            .perform(get(this.path(route)).with(apiPort()))
            .andReturn()
            .getResponse()
            .getStatus();

    assertThat(status).isEqualTo(401);
  }

  @ParameterizedTest(name = "POST {0}")
  @MethodSource("startRoutes")
  @DisplayName("starting a scan needs repo:write: scan:read and repo:read alone are 403")
  void startingAScan(final String route) throws Exception {
    final var path = "/api/repos/{repo}" + route;
    final var name = this.repo.getName();

    assertThat(this.statusOf(post(path, name), this.seedPat(this.user, TokenScope.SCAN_READ)))
        .isEqualTo(403);
    assertThat(this.statusOf(post(path, name), this.seedPat(this.user, TokenScope.REPO_READ)))
        .isEqualTo(403);
    assertThat(
            this.statusOf(
                post(path, name),
                this.seedPat(this.user, TokenScope.REPO_READ, TokenScope.SCAN_READ)))
        .isEqualTo(403);
    assertThat(this.statusOf(post(path, name), this.seedPat(this.user, TokenScope.REPO_WRITE)))
        .isNotIn(401, 403);
    assertThat(
            this.statusOf(
                post(path, name),
                this.seedPat(this.user, TokenScope.REPO_WRITE, TokenScope.SCAN_READ)))
        .isNotIn(401, 403);
  }

  @Test
  @DisplayName("scan:read alone does not read the repo, its permissions, or its package list")
  void scanReadDoesNotReadTheRepo() throws Exception {
    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);
    final var name = this.repo.getName();

    assertThat(this.statusOf(get("/api/repos"), scanOnly)).isEqualTo(403);
    assertThat(this.statusOf(get("/api/repos/{repo}", name), scanOnly)).isEqualTo(403);
    assertThat(this.statusOf(get("/api/repos/{repo}/permissions", name), scanOnly)).isEqualTo(403);
    assertThat(this.statusOf(get("/api/npm/packages/{repo}", name), scanOnly)).isEqualTo(403);

    // and the same token with repo:read does, so the 403 above is the scope and not the route
    final var reader = this.seedPat(this.user, TokenScope.REPO_READ);

    assertThat(this.statusOf(get("/api/repos/{repo}", name), reader)).isEqualTo(200);
    assertThat(this.statusOf(get("/api/npm/packages/{repo}", name), reader)).isEqualTo(200);
  }

  @Test
  @DisplayName("a repo that does not exist is 404 for scan:read and 403 for a token with neither")
  void unknownRepo() throws Exception {
    final var path = "/api/repos/{repo}/security-detail";

    final var scanOnly = this.seedPat(this.user, TokenScope.SCAN_READ);
    final var reader = this.seedPat(this.user, TokenScope.REPO_READ);
    final var neither = this.seedPat(this.user);

    assertThat(this.statusOf(get(path, "no-such-repo"), scanOnly)).isEqualTo(404);
    assertThat(this.statusOf(get(path, "no-such-repo"), reader)).isEqualTo(404);
    assertThat(this.statusOf(get(path, "no-such-repo"), neither)).isEqualTo(403);
  }

  @Test
  @DisplayName("the cross-repo scan browser is for a login: no scope of a token opens it")
  void theAdminScanBrowserStaysAdminOnly() throws Exception {
    final var admin = this.createUser(uniqueUsername("patscanadmin"), UserRole.ADMIN);

    final var paths =
        new String[] {
          "/api/security/scans", "/api/security/scans/summary", "/api/repos/security-summary"
        };

    for (final var path : paths) {
      for (final var owner : new User[] {this.user, admin}) {
        for (final var scopes :
            new TokenScope[][] {
              {TokenScope.SCAN_READ},
              {TokenScope.REPO_READ, TokenScope.SCAN_READ},
              {TokenScope.REPO_MANAGE, TokenScope.REPO_READ, TokenScope.SCAN_READ}
            }) {
          assertThat(this.statusOf(get(path), this.seedPat(owner, scopes)))
              .as("%s with %s of %s", path, scopes, owner.getRole())
              .isIn(401, 403);
        }
      }
    }
  }
}
