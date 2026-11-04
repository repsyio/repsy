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

package io.repsy.os.shared.error_handling;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.core.web_error.UrlMasking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RPS-2452: the masking itself is repsy-core's {@link UrlMasking} (its own tests live there). These
 * are the edge cases of the former OS copy that the repsy-core suite does not name, kept so that a
 * repsy-core bump that loosened one of them fails here, where the log lines that rely on it are
 * written.
 */
@DisplayName("The repsy-core UrlMasking keeps the edge cases the OS logs rely on")
class UrlMaskingEdgeCasesTest {

  private static final String SECRET = "s3cr3t-token-value";

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
    assertThat(UrlMasking.mask(input)).isEqualTo(expected);
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
    assertThat(UrlMasking.mask(input)).isEqualTo(expected);
  }

  @Test
  void aHostLongerThanADnsNameIsRedacted() {
    assertThat(UrlMasking.mask("https://" + "a".repeat(256) + "/x"))
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
    assertThat(UrlMasking.mask(input)).isEqualTo(expected);
  }

  @Test
  void maskUrlsKeepsLineBreaksAndNullAndEmpty() {
    assertThat(UrlMasking.maskUrls(null)).isNull();
    assertThat(UrlMasking.maskUrls("")).isEmpty();
    assertThat(UrlMasking.maskUrls("x\nhttps://u:" + SECRET + "@h/p\ny"))
        .isEqualTo("x\nhttps://h/...\ny");
  }

  @Test
  void rareControlCharactersAreEscaped() {
    assertThat(UrlMasking.mask("a\u007fb\u0085c\u2029d\u009fe"))
        .isEqualTo("a\\u007fb\\u0085c\\u2029d\\u009fe");
    assertThat(UrlMasking.mask("caf\u00e9 \u00a0")).isEqualTo("caf\u00e9 \u00a0");
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
    assertThat(UrlMasking.maskPath(path)).isEqualTo(expected);
  }

  @Test
  void maskPathKeepsEmptyPaths() {
    assertThat(UrlMasking.maskPath("")).isEmpty();
  }
}
