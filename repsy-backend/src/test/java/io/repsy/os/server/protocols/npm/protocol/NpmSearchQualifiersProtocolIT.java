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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1343 and RPS-1344: the {@code author:}, {@code maintainer:}, {@code is:}, {@code not:} and
 * {@code boost-exact:} qualifiers of {@code npm search} filter (a text of qualifiers only used to
 * match every package), and {@code size} and {@code from} follow the npm registry API. Packages are
 * published over the wire, so what is searched is what the real publish flow stored.
 */
@DisplayName("npm wire protocol GET /-/v1/search qualifiers and paging")
class NpmSearchQualifiersProtocolIT extends AbstractIntegrationTest {

  private static final String SEARCH = "/{repo}/-/v1/search";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;
  @Autowired private VulnerabilityScanRepository scanRepository;
  @Autowired private VulnerabilityFindingRepository findingRepository;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(
      final Repo repo,
      final String token,
      final String name,
      final String version,
      final Map<String, Object> extra)
      throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), name)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), name, version, extra)));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  private void setSecurityScan(final Repo repo, final boolean enabled) {
    final var stored = this.repoRepository.findById(repo.getId()).orElseThrow();
    stored.setSecurityScanEnabled(enabled);
    this.repoRepository.saveAndFlush(stored);
  }

  private void markDeprecated(final Repo repo, final String scope, final String name) {
    final var updated =
        this.jdbcTemplate.update(
            "update npm_package_version set deprecated = true where package_id = "
                + "(select id from npm_package where repo_id = ? and name = ? and "
                + "scope is not distinct from ?)",
            repo.getId(),
            name,
            scope);

    assertThat(updated).isEqualTo(1);
  }

  private void seedFinding(
      final Repo repo, final String name, final String version, final FixStatus fixStatus) {
    final var scan = new VulnerabilityScan();
    scan.setRepo(repo);
    scan.setArtifactName(name);
    scan.setArtifactVersion(version);
    scan.setStatus(ScanStatus.COMPLETED);
    scan.setScannerName("trivy");
    scan.setScannerVersion("0.58.1");
    final var saved = this.scanRepository.saveAndFlush(scan);

    final var finding = new VulnerabilityFinding();
    finding.setScan(saved);
    finding.setCveId("CVE-2026-0001");
    finding.setSeverity(Severity.HIGH);
    finding.setPackageName(name);
    finding.setPackageVersion(version);
    finding.setFixStatus(fixStatus);
    this.findingRepository.saveAndFlush(finding);
  }

  /**
   * A scanned repo with left-pad 1.0.0 (author Ann, maintainer ann), right-pad 0.3.0 (author and
   * maintainer bob), the deprecated @acme/widget 2.0.0 and is-odd 1.2.0 with a vulnerability.
   */
  private Repo seed(final String token) throws Exception {
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("qualifiers"), false, null);
    this.setSecurityScan(repo, true);

    this.publish(
        repo,
        token,
        "left-pad",
        "1.0.0",
        Map.of(
            "description", "pads a string on the left",
            "author", Map.of("name", "Ann", "email", "ann@example.com"),
            "maintainers", List.of(Map.of("name", "ann", "email", "ann@example.com"))));
    this.publish(
        repo,
        token,
        "right-pad",
        "0.3.0",
        Map.of(
            "description", "pads a string on the right",
            "author", Map.of("name", "Bob"),
            "maintainers", List.of(Map.of("name", "bob"))));
    this.publish(repo, token, "@acme/widget", "2.0.0", Map.of("description", "a widget"));
    this.publish(repo, token, "is-odd", "1.2.0", Map.of("description", "checks odd numbers"));
    this.markDeprecated(repo, "acme", "widget");
    this.seedFinding(repo, "is-odd", "1.2.0", FixStatus.AFFECTED);

    return this.reloadRepo(repo.getName());
  }

  private String adminToken() {
    return this.protocolBearerTokenFor(createUser(uniqueUsername("qualifiers"), UserRole.ADMIN));
  }

  private MockHttpServletResponse searchResponse(final Repo repo, final String... params)
      throws Exception {
    final var request = get(SEARCH, repo.getName());
    for (var i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }

    return this.protocol(request);
  }

  private String search(final Repo repo, final String... params) throws Exception {
    final var response = this.searchResponse(repo, params);

    assertThat(response.getStatus()).isEqualTo(200);

    return response.getContentAsString();
  }

  private static List<String> names(final String json) {
    final List<String> scopes = JsonPath.read(json, "$.objects[*].package.scope");
    final List<String> names = JsonPath.read(json, "$.objects[*].package.name");
    final var full = new ArrayList<String>();

    for (var i = 0; i < names.size(); i++) {
      full.add(
          "unscoped".equals(scopes.get(i))
              ? names.get(i)
              : "@" + scopes.get(i) + "/" + names.get(i));
    }

    return full;
  }

  private List<String> match(final Repo repo, final String text) throws Exception {
    return names(this.search(repo, "text", text));
  }

  @Test
  @DisplayName("author: matches the name or the email of the author, in any case")
  void author() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(this.match(repo, "author:ann")).containsExactly("left-pad");
    assertThat(this.match(repo, "author:ANN@example.com")).containsExactly("left-pad");
    assertThat(this.match(repo, "author:bob")).containsExactly("right-pad");
    assertThat(this.match(repo, "author:ann,bob"))
        .containsExactlyInAnyOrder("left-pad", "right-pad");
    assertThat(this.match(repo, "author:nobody")).isEmpty();
    assertThat(this.match(repo, "author:ann pad")).containsExactly("left-pad");
    assertThat(this.match(repo, "author:bob left")).isEmpty();
  }

  @Test
  @DisplayName("maintainer: matches the name or the email of a maintainer")
  void maintainer() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(this.match(repo, "maintainer:ann")).containsExactly("left-pad");
    assertThat(this.match(repo, "maintainer:ann@example.com")).containsExactly("left-pad");
    assertThat(this.match(repo, "maintainer:BOB")).containsExactly("right-pad");
    assertThat(this.match(repo, "maintainer:nobody")).isEmpty();
    assertThat(JsonPath.<Integer>read(this.search(repo, "text", "maintainer:nobody"), "$.total"))
        .isZero();
  }

  @Test
  @DisplayName("is:deprecated and not:deprecated split the packages on the flag of the latest")
  void deprecated() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(this.match(repo, "is:deprecated")).containsExactly("@acme/widget");
    assertThat(this.match(repo, "not:deprecated"))
        .containsExactlyInAnyOrder("left-pad", "right-pad", "is-odd");
    assertThat(this.match(repo, "is:deprecated widget")).containsExactly("@acme/widget");
    assertThat(this.match(repo, "not:deprecated widget")).isEmpty();
  }

  @Test
  @DisplayName("is:unstable and not:unstable split the packages at version 1.0.0")
  void unstable() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(this.match(repo, "is:unstable")).containsExactly("right-pad");
    assertThat(this.match(repo, "not:unstable"))
        .containsExactlyInAnyOrder("left-pad", "@acme/widget", "is-odd");
  }

  @Test
  @DisplayName("is:insecure and not:insecure follow the vulnerability scan of the latest version")
  void insecure() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(this.match(repo, "is:insecure")).containsExactly("is-odd");
    assertThat(this.match(repo, "not:insecure"))
        .containsExactlyInAnyOrder("left-pad", "right-pad", "@acme/widget");
    assertThat(this.match(repo, "is:insecure not:deprecated")).containsExactly("is-odd");
    assertThat(this.match(repo, "is:insecure is:deprecated")).isEmpty();
  }

  @Test
  @DisplayName("a finding that says the version is not affected does not make a package insecure")
  void notAffectedIsNotInsecure() throws Exception {
    final var token = this.adminToken();
    final var repo = this.seed(token);
    this.publish(repo, token, "fine-pkg", "1.0.0", Map.of());
    this.seedFinding(repo, "fine-pkg", "1.0.0", FixStatus.NOT_AFFECTED);

    assertThat(this.match(repo, "is:insecure")).containsExactly("is-odd");
    assertThat(this.match(repo, "not:insecure fine")).containsExactly("fine-pkg");
  }

  @Test
  @DisplayName("a repo that is not scanned has nothing insecure, as its audit reports no advisory")
  void insecureWithoutASecurityScan() throws Exception {
    final var repo = this.seed(this.adminToken());
    this.setSecurityScan(repo, false);

    assertThat(this.match(repo, "is:insecure")).isEmpty();
    assertThat(this.match(repo, "not:insecure"))
        .containsExactlyInAnyOrder("left-pad", "right-pad", "@acme/widget", "is-odd");
  }

  @Test
  @DisplayName("a text of qualifiers Repsy cannot filter on matches no package")
  void qualifierOnlyTextMatchesNothing() throws Exception {
    final var repo = this.seed(this.adminToken());

    for (final var text :
        List.of(
            "is:shiny", "not:shiny", "author:", "maintainer:", "boost-exact:maybe", "keywords:")) {
      final var json = this.search(repo, "text", text);

      assertThat(names(json)).as(text).isEmpty();
      assertThat(JsonPath.<Integer>read(json, "$.total")).as(text).isZero();
    }

    assertThat(this.match(repo, "is:shiny left")).containsExactly("left-pad");
    assertThat(this.match(repo, "")).hasSize(4);
    assertThat(this.match(repo, "boost-exact:false")).hasSize(4);
  }

  @Test
  @DisplayName("boost-exact:false takes the whole-name bonus away from the score")
  void boostExact() throws Exception {
    final var repo = this.seed(this.adminToken());

    final var boosted = this.search(repo, "text", "left-pad");
    final var plain = this.search(repo, "text", "left-pad boost-exact:false");

    assertThat(JsonPath.<Double>read(boosted, "$.objects[0].searchScore")).isGreaterThan(100_000.0);
    assertThat(JsonPath.<Double>read(plain, "$.objects[0].searchScore")).isLessThan(100_000.0);
    assertThat(names(plain)).containsExactly("left-pad");
  }

  @Test
  @DisplayName("size=0 answers no objects and the total (RPS-1344)")
  void sizeZero() throws Exception {
    final var repo = this.seed(this.adminToken());

    final var json = this.search(repo, "size", "0");

    assertThat(names(json)).isEmpty();
    assertThat(JsonPath.<Integer>read(json, "$.total")).isEqualTo(4);
    assertThat(names(this.search(repo, "text", "pad", "size", "0"))).isEmpty();
    assertThat(JsonPath.<Integer>read(this.search(repo, "text", "pad", "size", "0"), "$.total"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a from beyond the last result, however large, answers an empty page (RPS-1344)")
  void fromBeyondTheEnd() throws Exception {
    final var repo = this.seed(this.adminToken());

    for (final var from : List.of("4", "10", "2147483647", "99999999999", "99999999999999999999")) {
      final var json = this.search(repo, "from", from);

      assertThat(names(json)).as(from).isEmpty();
      assertThat(JsonPath.<Integer>read(json, "$.total")).as(from).isEqualTo(4);
    }
    assertThat(names(this.search(repo, "from", "3"))).hasSize(1);
  }

  @Test
  @DisplayName("a size above 250 is cut down to 250, and a blank size or from takes the default")
  void sizeAboveTheMaximum() throws Exception {
    final var repo = this.seed(this.adminToken());

    assertThat(names(this.search(repo, "size", "251"))).hasSize(4);
    assertThat(names(this.search(repo, "size", "99999999999"))).hasSize(4);
    assertThat(names(this.search(repo, "size", "", "from", ""))).hasSize(4);
  }

  @Test
  @DisplayName("a size or from that is no whole number of 0 or more is a 400, not a default")
  void invalidSizeOrFrom() throws Exception {
    final var repo = this.seed(this.adminToken());

    for (final var params :
        List.of(
            new String[] {"size", "many"},
            new String[] {"size", "-1"},
            new String[] {"size", "1.5"},
            new String[] {"from", "x"},
            new String[] {"from", "-3"},
            new String[] {"from", "-99999999999"},
            new String[] {"from", "1e3"})) {
      final var response = this.searchResponse(repo, params);

      assertThat(response.getStatus()).as(String.join("=", params)).isEqualTo(400);
      assertThat(response.getContentAsString()).contains("invalidSearchParameter");
    }
  }
}
