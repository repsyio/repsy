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
package io.repsy.protocols.npm.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.npm.protocol.handlers.NpmHandlerTestSupport.FixedBaseParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;

@DisplayName("NpmExactPathParser")
class NpmExactPathParserTest {

  private static NpmExactPathParser parser(final FixedBaseParser base, final String regex) {
    return new NpmExactPathParser(base, HttpMethod.GET, regex);
  }

  @ParameterizedTest(name = "{0} -> matches={1}")
  @CsvSource({
    "/npm/-/whoami,             true",
    "/npm/-/whoami/x,           false",
    "/npm/-/whoami/,            false",
    "/npm/whoami,               false",
    "/npm/-whoami,              false",
    "/npm/-/-/--1.0.0.tgz,      false",
    "/npm/@scope/-/whoami,      false",
    "/-/whoami,                 false",
    "/npm/a/-/whoami,           false"
  })
  @DisplayName("claims only the exact endpoint below a repository name")
  void claimsOnlyTheExactEndpoint(final String servletPath, final boolean matches) {
    // The base parser would accept the relative path of a real request; only the regexes decide.
    final var relative =
        servletPath.substring(servletPath.indexOf('/', 1) < 0 ? 0 : servletPath.indexOf('/', 1));
    final var base = new FixedBaseParser(relative, true);

    final var result =
        parser(base, "/-/whoami").parse(NpmHandlerTestSupport.request("GET", servletPath));

    assertThat(result.isPresent()).isEqualTo(matches);
  }

  @Test
  @DisplayName("does not ask the base parser, which costs a database query, for another path")
  void doesNotLookTheRepositoryUpForAnotherPath() {
    final var base = new FixedBaseParser("/-/whoami", true);

    assertThat(
            parser(base, "/-/whoami").parse(NpmHandlerTestSupport.request("GET", "/npm/left-pad")))
        .isEmpty();
    assertThat(base.calls()).isZero();
  }

  @Test
  @DisplayName("does not ask the base parser for another HTTP method")
  void leavesOtherMethodsAlone() {
    final var base = new FixedBaseParser("/-/whoami", true);

    assertThat(
            parser(base, "/-/whoami").parse(NpmHandlerTestSupport.request("POST", "/npm/-/whoami")))
        .isEmpty();
    assertThat(base.calls()).isZero();
  }

  @Test
  @DisplayName("is empty when the repository is unknown")
  void followsTheBaseParser() {
    final var base = new FixedBaseParser("/-/whoami", false);

    assertThat(
            parser(base, "/-/whoami").parse(NpmHandlerTestSupport.request("GET", "/npm/-/whoami")))
        .isEmpty();
    assertThat(base.calls()).isEqualTo(1);
  }

  @Test
  @DisplayName("checks the relative path the base parser resolved as well")
  void checksTheRelativePath() {
    final var base = new FixedBaseParser("/-/other", true);

    assertThat(
            parser(base, "/-/whoami").parse(NpmHandlerTestSupport.request("GET", "/npm/-/whoami")))
        .isEmpty();
  }

  @Test
  @DisplayName("takes an alternative in the regex")
  void takesAlternatives() {
    final var regex = "/-/npm/v1/security/audits(?:/quick)?";
    final var audits = new FixedBaseParser("/-/npm/v1/security/audits", true);
    final var quick = new FixedBaseParser("/-/npm/v1/security/audits/quick", true);

    assertThat(
            new NpmExactPathParser(audits, HttpMethod.POST, regex)
                .parse(NpmHandlerTestSupport.request("POST", "/npm/-/npm/v1/security/audits")))
        .isPresent();
    assertThat(
            new NpmExactPathParser(quick, HttpMethod.POST, regex)
                .parse(
                    NpmHandlerTestSupport.request("POST", "/npm/-/npm/v1/security/audits/quick")))
        .isPresent();
    assertThat(
            new NpmExactPathParser(quick, HttpMethod.POST, regex)
                .parse(NpmHandlerTestSupport.request("POST", "/npm/-/npm/v1/security/audits/slow")))
        .isEmpty();
  }
}
