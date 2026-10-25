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

import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Recognises the requests that no Repsy client sends and a scanner does (RPS-2319). It is pure: it
 * looks at the method, the raw request URI and the port, never at a body or a header, and does no
 * regular-expression matching on them, so the cost is a few linear scans however hostile the input.
 *
 * <p>The URI is looked at as sent, and percent-decoded once and twice, so an encoded {@code %2e%2e}
 * cannot hide. {@code %2f} is never a signal on its own: npm sends {@code @scope%2fpkg}.
 */
@Component
public class SuspiciousRequestClassifier {

  /** Longest URI prefix that is inspected; a longer URI is cut, not skipped. */
  static final int MAX_INSPECTED_LENGTH = 4096;

  private static final Set<String> ABUSED_METHODS =
      Set.of("TRACE", "TRACK", "CONNECT", "PROPFIND", "PROPPATCH", "MKCOL", "DEBUG");

  private static final List<String> SECRET_NEEDLES =
      List.of(
          "/.env",
          "/proc/self/",
          "/etc/passwd",
          "/.git/",
          "/.aws/",
          "/.ssh/",
          "/web.config",
          "/actuator/env");

  private static final int HEX_RADIX = 16;
  private static final int NIBBLE_BITS = 4;

  private final SuspiciousRequestProperties properties;
  private final Map<SignalFamily, Predicate<Request>> detectors = new EnumMap<>(SignalFamily.class);

  public SuspiciousRequestClassifier(final SuspiciousRequestProperties properties) {

    this.properties = properties;
    this.detectors.put(SignalFamily.TRAVERSAL, SuspiciousRequestClassifier::isTraversal);
    this.detectors.put(SignalFamily.SECRET_PATH, SuspiciousRequestClassifier::isSecretPath);
    this.detectors.put(SignalFamily.NULL_BYTE, request -> request.twice().indexOf('\0') >= 0);
    this.detectors.put(
        SignalFamily.METHOD_ABUSE,
        request -> ABUSED_METHODS.contains(request.method().toUpperCase(Locale.ROOT)));
  }

  /** The families of suspicious signals in the request, only those switched on; usually none. */
  public Set<SignalFamily> classify(
      final String method, final String rawUri, final boolean apiPort) {

    final var uri =
        rawUri.length() > MAX_INSPECTED_LENGTH ? rawUri.substring(0, MAX_INSPECTED_LENGTH) : rawUri;
    final var lower = uri.toLowerCase(Locale.ROOT);
    final var once = decode(lower);
    final var request = new Request(method, lower, once, decode(once));
    final var found = EnumSet.noneOf(SignalFamily.class);

    for (final var detector : this.detectors.entrySet()) {
      if (this.isOn(detector.getKey(), apiPort) && detector.getValue().test(request)) {
        found.add(detector.getKey());
      }
    }

    return found;
  }

  private boolean isOn(final SignalFamily family, final boolean apiPort) {

    return (apiPort || !family.apiPortOnly()) && this.properties.weight(family) > 0;
  }

  private static boolean isTraversal(final Request request) {

    return hasDotDotSegment(request.lower())
        || hasDotDotSegment(request.once())
        || hasDotDotSegment(request.twice());
  }

  private static boolean isSecretPath(final Request request) {

    final var path = pathOf(request.twice());

    return path.endsWith("/.git") || SECRET_NEEDLES.stream().anyMatch(path::contains);
  }

  /** {@code ..} as a whole segment, also as {@code ..;param}, with {@code /} or {@code \}. */
  private static boolean hasDotDotSegment(final String uri) {

    return Arrays.stream(pathOf(uri).replace('\\', '/').split("/", -1))
        .anyMatch(segment -> segment.equals("..") || segment.startsWith("..;"));
  }

  private static String pathOf(final String uri) {

    final var query = uri.indexOf('?');

    return query < 0 ? uri : uri.substring(0, query);
  }

  /** The request as the classifier sees it: lower case, as sent and decoded once and twice. */
  private record Request(String method, String lower, String once, String twice) {}

  /** One bounded pass of percent-decoding; a malformed escape stays as it is, {@code +} too. */
  static String decode(final String value) {

    if (value.indexOf('%') < 0) {
      return value;
    }

    final var out = new StringBuilder(value.length());

    for (var i = 0; i < value.length(); i++) {
      final var escaped = escapedByte(value, i);

      if (escaped < 0) {
        out.append(value.charAt(i));
      } else {
        out.append((char) escaped);
        i += 2;
      }
    }

    return out.toString();
  }

  /** The byte of the {@code %xx} escape at {@code index}, or -1 when there is none. */
  private static int escapedByte(final String value, final int index) {

    if (value.charAt(index) != '%' || index + 2 >= value.length()) {
      return -1;
    }

    final var high = hex(value.charAt(index + 1));
    final var low = hex(value.charAt(index + 2));

    return high < 0 || low < 0 ? -1 : (high << NIBBLE_BITS) | low;
  }

  private static int hex(final char c) {

    return Character.digit(c, HEX_RADIX);
  }
}
