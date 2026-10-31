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

package io.repsy.protocols.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

@DisplayName("UrlMaskingUtils removes credentials from URLs before they are logged")
class UrlMaskingUtilsTest {

  private static final String SECRET = "s3cr3t-token-value";

  /** The first version of the masking (RPS-2174), kept to show that the examples below leaked. */
  private static final Pattern OLD_PATTERN =
      Pattern.compile(
          "([a-zA-Z][a-zA-Z0-9+.\\-]*://)([^\\s/?#@\"'<>]*@)?([^\\s?#\"'<>]*)([?#][^\\s\"'<>]*)?");

  private static String oldMask(final String text) {
    return OLD_PATTERN.matcher(text).replaceAll(match -> match.group(1) + match.group(3));
  }

  static Stream<Arguments> wellFormed() {
    return Stream.of(
        Arguments.of(
            "https://bob:" + SECRET + "@repo.example.com/maven/releases",
            "https://repo.example.com/..."),
        Arguments.of(
            "https://repo.example.com:8443/a/b?token=" + SECRET + "#frag",
            "https://repo.example.com:8443/..."),
        Arguments.of("https://repo.example.com", "https://repo.example.com"),
        Arguments.of(
            "HTTPS://bob:" + SECRET + "@Repo.Example.com/x", "HTTPS://Repo.Example.com/..."),
        Arguments.of("http://[::1]:8080/p?k=" + SECRET, "http://[::1]:8080/..."),
        Arguments.of("http://u:" + SECRET + "@[2001:db8::1]/p", "http://[2001:db8::1]/..."),
        Arguments.of("Connection refused", "Connection refused"),
        Arguments.of(
            "I/O error on GET request for \"https://bob:"
                + SECRET
                + "@host/x?key="
                + SECRET
                + "\": Connection reset",
            "I/O error on GET request for \"https://host/...\": Connection reset"),
        Arguments.of(
            "a https://u:" + SECRET + "@one/x and http://two/y?k=" + SECRET + " done",
            "a https://one/... and http://two/... done"));
  }

  static Stream<String> hostile() {
    return Stream.of(
        "https://user:p@" + SECRET + "@host/x",
        "https://user:" + SECRET + "#-x@host/",
        "https://user:" + SECRET + "?x@host/",
        "https://user:" + SECRET + "/x@host/",
        "https://host\\" + SECRET + "@evil/",
        "https://user:" + SECRET + "%40x@host/",
        "https://user:" + SECRET + "%40host/x",
        "https://user:" + SECRET + " more@host/x",
        "https://user:" + SECRET + "\nmore@host/x",
        "https://user:" + SECRET + "/x",
        "https://" + SECRET + "@/x",
        "https://[" + SECRET + "]/x",
        "https://[::1]" + SECRET + "/x",
        "https://host:" + SECRET + "/x",
        "https://u:" + SECRET + "@h1/x https://u:" + SECRET + "@h2/y?" + SECRET,
        "ftp://u:" + SECRET + "@h/x");
  }

  @ParameterizedTest
  @MethodSource("wellFormed")
  void wellFormedUrlsAreReducedToHostAndPort(final String input, final String expected) {
    assertThat(UrlMaskingUtils.mask(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("hostile")
  void nothingOfTheAuthorityBeforeTheLastAtSignSurvives(final String input) {
    assertThat(UrlMaskingUtils.mask(input)).doesNotContain("s3cr3t").doesNotContain("%40");
  }

  /** Flip-and-fail: the old pattern echoed a remnant of the secret for these. */
  @ParameterizedTest
  @MethodSource("oldPatternLeaks")
  void theOldPatternLeaked(final String input) {
    assertThat(oldMask(input)).contains("s3cr3t");
    assertThat(UrlMaskingUtils.mask(input)).doesNotContain("s3cr3t");
  }

  static Stream<String> oldPatternLeaks() {
    return Stream.of(
        "https://user:p@" + SECRET + "@host/x",
        "https://user:" + SECRET + "#-x@host/",
        "https://user:" + SECRET + "?x@host/",
        "https://user:" + SECRET + "/x@host/",
        "https://user:" + SECRET + " more@host/x",
        "https://user:" + SECRET + "/x");
  }

  @ParameterizedTest
  @NullAndEmptySource
  void nullAndEmptyAreKept(final String input) {
    assertThat(UrlMaskingUtils.mask(input)).isEqualTo(input);
  }

  @Test
  @Timeout(5)
  void aVeryLongTokenIsScannedInLinearTime() {
    final var longToken = "x".repeat(200_000);

    final var inputs =
        new String[] {
          "https://" + longToken,
          "https://" + longToken + "@host/x",
          "https://u:" + longToken + "/x",
          longToken,
          "https://".repeat(25_000),
          "a://".repeat(50_000),
          ("https://" + "a".repeat(50) + " ").repeat(4_000)
        };

    final long started = System.nanoTime();

    for (final var input : inputs) {
      assertThat(UrlMaskingUtils.mask(input)).isNotNull();
    }

    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void controlCharactersAreEscapedSoALogLineCannotBeForged() {
    final var masked =
        UrlMaskingUtils.mask("failed\r\n2026-01-01 ERROR forged\u2028line https://h/x\ty\u0000z");

    assertThat(masked)
        .isEqualTo("failed\\r\\n2026-01-01 ERROR forged\\u2028line https://h/...\\ty\\u0000z")
        .doesNotContain("\r", "\n", "\t", "\u0000", "\u2028");
  }

  @ParameterizedTest
  @CsvSource({
    "/-/user/token/s3cr3t-token-value, /-/user/token/***",
    "/api/repos/o/r/download-token/s3cr3t-token-value/x, /api/repos/o/r/download-token/***/***",
    "/files/signed/s3cr3t-token-value, /files/signed/***",
    "/api/profile/access-tokens/42, /api/profile/access-tokens/42",
    "/api/surveys/7, /api/surveys/7"
  })
  void secretPathSegmentsAreMasked(final String path, final String expected) {
    assertThat(UrlMaskingUtils.maskPath(path)).isEqualTo(expected);
  }

  // The container normalises the path before routing, the log keeps the raw URI (RPS-2203): every
  // segment after a marker is masked, so a dot, empty or parameter segment cannot expose the
  // secret.
  @ParameterizedTest
  @CsvSource({
    "/-/user/token/./s3cr3t-token-value, /-/user/token/***/***",
    "/token/x/../s3cr3t-token-value, /token/***/***/***",
    "/token//s3cr3t-token-value, /token/***/***",
    "/token/;a=1/s3cr3t-token-value, /token/***/***",
    "/token/%2e/s3cr3t-token-value, /token/***/***",
    "/token/%2E%2e/s3cr3t-token-value, /token/***/***",
    "/token/, /token/***",
    "/a/b/c, /a/b/c"
  })
  void everySegmentAfterAMarkerIsMasked(final String path, final String expected) {
    assertThat(UrlMaskingUtils.maskPath(path)).isEqualTo(expected);
  }

  // The logged path is the raw request URI; the route matched the decoded, parameter-free one.
  @ParameterizedTest
  @CsvSource({
    "/-/user/%74oken/s3cr3t-token-value, /-/user/%74oken/***",
    "/-/user/%54OKEN/s3cr3t-token-value, /-/user/%54OKEN/***",
    "/-/user/%2574oken/s3cr3t-token-value, /-/user/%2574oken/***",
    "/-/user/token;v=1/s3cr3t-token-value, /-/user/token;v=1/***",
    "/-/user/download%2Dtoken/s3cr3t-token-value, /-/user/download%2Dtoken/***",
    "/-/user/to%ken/s3cr3t-token-value, /-/user/to%ken/s3cr3t-token-value",
    "/-/user/%7oken/s3cr3t-token-value, /-/user/%7oken/s3cr3t-token-value",
    "/-/user/%zz/s3cr3t-token-value, /-/user/%zz/s3cr3t-token-value"
  })
  void encodedOrParameterisedMarkersAreStillRecognised(final String path, final String expected) {
    assertThat(UrlMaskingUtils.maskPath(path)).isEqualTo(expected);
  }

  @Test
  void aPathIsCutAndHasItsControlCharactersEscaped() {
    assertThat(UrlMaskingUtils.maskPath("/a/" + "x".repeat(10_000))).hasSizeLessThanOrEqualTo(512);
    assertThat(UrlMaskingUtils.maskPath("/a\r\nERROR forged")).isEqualTo("/a\\r\\nERROR forged");
    assertThat(UrlMaskingUtils.maskPath(null)).isNull();
  }

  // Anything that is not a plain host (and port) is not echoed: it may be a secret.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "https://[]/x|https://<redacted>/...",
        "https://[::1/x|https://<redacted>/...",
        "https://[zz]/x|https://<redacted>/...",
        "https://[::1]:123456/x|https://<redacted>/...",
        "https://[::1]:80/x|https://[::1]:80/...",
        "https://host:/x|https://<redacted>/...",
        "https://host:80a/x|https://<redacted>/...",
        "https://host:8080/x|https://host:8080/...",
        "https://:80/x|https://<redacted>/...",
        "https://ho$t/x|https://<redacted>/...",
        "https:///x|https://<redacted>/..."
      })
  void anAuthorityThatIsNotAPlainHostIsRedacted(final String input, final String expected) {
    assertThat(UrlMaskingUtils.mask(input)).isEqualTo(expected);
  }

  // The accepted characters of a host, an IPv6 literal and a scheme, and the ends of a URL.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "https://my_host-1.example.com/p|https://my_host-1.example.com/...",
        "https://[::FFFF:1.2.3.4]/p|https://[::FFFF:1.2.3.4]/...",
        "https://[::1/]x|https://<redacted>/...",
        "git+ssh://h/x|git+ssh://h/...",
        "a-b.c://h/x|a-b.c://h/...",
        "https://h?x=1|https://h/...",
        "https://h#frag|https://h/...",
        "see <https://h/x> and 'https://h2/y' ok|see <https://h/...> and 'https://h2/...' ok",
        "\"https://h/x\" and (https://h2:80)|\"https://h/...\" and (https://<redacted>"
      })
  void hostSchemeAndUrlEndsAreRecognised(final String input, final String expected) {
    assertThat(UrlMaskingUtils.mask(input)).isEqualTo(expected);
  }

  @Test
  void aHostLongerThanADnsNameIsRedacted() {
    assertThat(UrlMaskingUtils.mask("https://" + "a".repeat(256) + "/x"))
        .isEqualTo("https://<redacted>/...");
  }

  // The scheme starts at the first letter: digits and signs in front of it are not part of it.
  @ParameterizedTest
  @CsvSource({
    "1http://host/x, 1http://host/...",
    "+-http://host/x, +-http://host/...",
    "123://host/x, 123://host/x",
    "://host/x, ://host/x"
  })
  void theSchemeStartsAtTheFirstLetter(final String input, final String expected) {
    assertThat(UrlMaskingUtils.mask(input)).isEqualTo(expected);
  }

  @Test
  void maskUrlsKeepsLineBreaksAndNullAndEmpty() {
    assertThat(UrlMaskingUtils.maskUrls(null)).isNull();
    assertThat(UrlMaskingUtils.maskUrls("")).isEmpty();
    assertThat(UrlMaskingUtils.maskUrls("x\nhttps://u:" + SECRET + "@h/p\ny"))
        .isEqualTo("x\nhttps://h/...\ny");
  }

  @Test
  void rareControlCharactersAreEscaped() {
    assertThat(UrlMaskingUtils.mask("a\u007fb\u0085c\u2029d\u009fe"))
        .isEqualTo("a\\u007fb\\u0085c\\u2029d\\u009fe");
    assertThat(UrlMaskingUtils.mask("caf\u00e9 \u00a0")).isEqualTo("caf\u00e9 \u00a0");
  }

  // Only %HH that stands for an ASCII character is decoded (to find the marker); the rest stays.
  @ParameterizedTest
  @CsvSource({
    "/token/%C3%A9/s3cr3t-token-value, /token/***/***",
    "/%C3token/x, /%C3token/x",
    "/x%4, /x%4",
    "/x%, /x%"
  })
  void nonAsciiAndTruncatedPercentSequencesAreLeftAlone(final String path, final String expected) {
    assertThat(UrlMaskingUtils.maskPath(path)).isEqualTo(expected);
  }

  @Test
  void maskPathKeepsEmptyPaths() {
    assertThat(UrlMaskingUtils.maskPath("")).isEmpty();
  }

  @Test
  void theUtilityClassCannotBeInstantiated() throws ReflectiveOperationException {
    final var constructor = UrlMaskingUtils.class.getDeclaredConstructor();
    constructor.setAccessible(true);

    assertThatThrownBy(constructor::newInstance)
        .isInstanceOf(InvocationTargetException.class)
        .hasCauseInstanceOf(UnsupportedOperationException.class);
  }
}
