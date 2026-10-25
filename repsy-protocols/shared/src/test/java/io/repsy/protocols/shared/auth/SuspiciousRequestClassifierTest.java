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

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SuspiciousRequestClassifier")
class SuspiciousRequestClassifierTest {

  private final SuspiciousRequestClassifier classifier =
      new SuspiciousRequestClassifier(
          SuspiciousRequestProperties.of(AuthThrottleMode.ENFORCE, 10, 3600));

  private Set<SignalFamily> api(final String uri) {

    return this.classifier.classify("GET", uri, true);
  }

  private Set<SignalFamily> protocol(final String method, final String uri) {

    return this.classifier.classify(method, uri, false);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/orders/../../proc/self/environ",
        "/api/data/../../.env",
        "/api/w/admins/jobs_u/get_log_file/../../../../proc/self/environ",
        "/api/%2e%2e/%2e%2e/etc/passwd",
        "/api/%2E%2E/secret",
        "/api/..%2f..%2fsecret",
        "/api/..%5c..%5csecret",
        "/api/..;/secret",
        "/api/%252e%252e/secret",
        "/api/..\\..\\secret",
        "/api/x/..",
        "/api/x/%2e%2e",
        "/..%2fsecret",
      })
  @DisplayName("a dot-dot segment is a traversal however it is encoded")
  void traversalIsRecognised(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.TRAVERSAL);
    assertThat(this.protocol("GET", uri)).contains(SignalFamily.TRAVERSAL);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/repo/com/example/lib/1.0-SNAPSHOT/maven-metadata.xml",
        "/repo/com/example/a..b/1.0/a..b-1.0.pom",
        "/repo/@scope%2fpkg",
        "/repo/@scope%2fpkg/-/pkg-1.0.0.tgz",
        "/repo/-/v1/search?text=select+union&size=20",
        "/repo/github.com/!foo/bar/@v/v0.0.0-20240101000000-abcdef.info",
        "/packages/chart.js",
        "/repo/dotenv/-/dotenv-16.0.0.tgz",
        "/v2/repo/img/manifests/sha256:abc",
        "/v2/repo/img/blobs/uploads/abc?digest=sha256%3Aabcdef",
        "/repo/v3/query?q=newtonsoft&prerelease=true&semVerLevel=2.0.0",
        "/repo/simple/requests/",
        "/repo/index.yaml",
        "/repo/api/v1/crates?q=serde&per_page=10",
        "/api/users?q=..",
        "/api/repos/my.repo.name/settings",
        "/",
      })
  @DisplayName("what Repsy's own clients send is no signal on the protocol port")
  void legitimateTrafficIsNoSignal(final String uri) {

    assertThat(this.protocol("GET", uri)).isEmpty();
    assertThat(this.protocol("PUT", uri)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/.env",
        "/api/x/.env",
        "/api/orders/../../.env",
        "/api/x/%2eenv",
        "/proc/self/environ",
        "/etc/passwd",
        "/.git/config",
        "/x/.git",
        "/.aws/credentials",
        "/.ssh/id_rsa",
        "/web.config",
        "/actuator/env",
        "/api/%252eenv",
      })
  @DisplayName("a secret-hunting path is a signal on the API port only")
  void secretPathIsRecognisedOnTheApiPortOnly(final String uri) {

    assertThat(this.api(uri)).contains(SignalFamily.SECRET_PATH);
    assertThat(this.protocol("GET", uri)).doesNotContain(SignalFamily.SECRET_PATH);
  }

  @Test
  @DisplayName("a query string does not make a secret path")
  void secretPathLooksAtThePathOnly() {

    assertThat(this.api("/api/users?redirect=/.env")).isEmpty();
  }

  @Test
  @DisplayName("a NUL byte is a signal on both ports")
  void nullByteIsRecognised() {

    assertThat(this.api("/api/x%00.png")).contains(SignalFamily.NULL_BYTE);
    assertThat(this.protocol("GET", "/repo/x%2500.png")).contains(SignalFamily.NULL_BYTE);
    assertThat(this.api("/api/users")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRACE", "track", "CONNECT", "PROPFIND", "PROPPATCH", "MKCOL", "DEBUG"})
  @DisplayName("a method no route accepts is a signal on the API port only")
  void abusedMethodIsRecognisedOnTheApiPortOnly(final String method) {

    assertThat(this.classifier.classify(method, "/api/users", true))
        .containsExactly(SignalFamily.METHOD_ABUSE);
    assertThat(this.classifier.classify(method, "/repo/x", false)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"GET", "HEAD", "PUT", "POST", "PATCH", "DELETE", "OPTIONS"})
  @DisplayName("the methods the clients use are no signal")
  void ordinaryMethodsAreNoSignal(final String method) {

    assertThat(this.classifier.classify(method, "/api/users", true)).isEmpty();
  }

  @Test
  @DisplayName("several signals of one request are all reported")
  void severalSignalsAreReported() {

    assertThat(this.classifier.classify("TRACE", "/api/../.env", true))
        .isEqualTo(
            EnumSet.of(
                SignalFamily.TRAVERSAL, SignalFamily.SECRET_PATH, SignalFamily.METHOD_ABUSE));
  }

  @Test
  @DisplayName("a family that is switched off is never reported")
  void switchedOffFamilyIsSkipped() {

    final var properties =
        new SuspiciousRequestProperties(
            AuthThrottleMode.ENFORCE,
            10,
            60L,
            3600L,
            10_000L,
            null,
            Map.of("traversal", new SuspiciousRequestProperties.Signal(false, null, null)));
    final var withoutTraversal = new SuspiciousRequestClassifier(properties);

    assertThat(withoutTraversal.classify("GET", "/api/../x", true)).isEmpty();
  }

  @Test
  @DisplayName("an overlong URI is cut, not skipped, and does not blow up")
  void overlongUriIsCut() {

    final var tail = "/..".repeat(10);
    final var longUri =
        "/api/" + "a".repeat(SuspiciousRequestClassifier.MAX_INSPECTED_LENGTH) + tail;
    final var headed = "/api/../" + "a".repeat(SuspiciousRequestClassifier.MAX_INSPECTED_LENGTH);

    assertThat(this.api(longUri)).containsExactly(SignalFamily.OVERLONG_URI);
    assertThat(this.api(headed)).contains(SignalFamily.TRAVERSAL);
  }

  @Test
  @DisplayName("a malformed percent escape stays as it is")
  void malformedEscapeIsKept() {

    assertThat(SuspiciousRequestClassifier.decode("/a%zz%2")).isEqualTo("/a%zz%2");
    assertThat(SuspiciousRequestClassifier.decode("/a%2e%2E")).isEqualTo("/a..");
  }
}
