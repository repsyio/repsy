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

  /** URIs longer than this are an {@link SignalFamily#OVERLONG_URI}; no Repsy client sends one. */
  static final int OVERLONG_URI_LENGTH = 2048;

  private static final List<String> SCRIPT_EXTENSIONS =
      List.of(".php", ".asp", ".aspx", ".jsp", ".jspx", ".cgi", ".phtml", ".php5", ".cfm");

  private static final List<String> SCRIPT_NEEDLES =
      List.of("/wp-admin", "/wp-login", "/wp-content", "/wp-includes", "/phpmyadmin", "/cgi-bin/");

  private static final List<String> SQLI_NEEDLES =
      List.of(
          "union select",
          "union all select",
          "sleep(",
          "pg_sleep(",
          "benchmark(",
          "waitfor delay",
          "or 1=1",
          "' or '",
          "information_schema",
          "; drop table");

  private static final List<String> XSS_NEEDLES =
      List.of("<script", "javascript:", "onerror=", "onload=", "<svg", "<iframe");

  private static final List<String> CMDI_NEEDLES =
      List.of("$(", "`", "/bin/sh", "/bin/bash", "cmd.exe", "powershell", ";wget ", ";curl ");

  private static final List<String> SSRF_NEEDLES =
      List.of("169.254.169.254", "metadata.google.internal", "file://", "gopher://", "dict://");

  private static final List<String> LOG4SHELL_NEEDLES =
      List.of("${jndi:", "${lower:", "${upper:", "${env:", "${sys:", "${java:", "${::-");

  private static final List<String> SSTI_NEEDLES =
      List.of("{{7*7}}", "${7*7}", "#{7*7}", "<%=", "{{config", "{{self");

  private static final List<String> SCANNER_AGENTS =
      List.of(
          "sqlmap",
          "nikto",
          "nmap",
          "masscan",
          "zgrab",
          "nuclei",
          "gobuster",
          "dirbuster",
          "feroxbuster",
          "ffuf",
          "wpscan",
          "acunetix",
          "nessus",
          "havij",
          "whatweb");

  private static final String API_PREFIX = "/api/";
  private static final String ENCODED_PERCENT = "%25";
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
    this.detectors.put(SignalFamily.SCRIPT_TARGET, SuspiciousRequestClassifier::isScriptTarget);
    this.detectors.put(
        SignalFamily.SQLI, request -> containsAny(request.pathAndQuery(), SQLI_NEEDLES));
    this.detectors.put(
        SignalFamily.XSS, request -> containsAny(request.pathAndQuery(), XSS_NEEDLES));
    this.detectors.put(SignalFamily.CMDI, request -> containsAny(request.query(), CMDI_NEEDLES));
    this.detectors.put(
        SignalFamily.SSRF_URL, request -> containsAny(request.query(), SSRF_NEEDLES));
    this.detectors.put(
        SignalFamily.LOG4SHELL, request -> containsAny(request.everything(), LOG4SHELL_NEEDLES));
    this.detectors.put(
        SignalFamily.SSTI, request -> containsAny(request.pathAndQuery(), SSTI_NEEDLES));
    this.detectors.put(SignalFamily.DOUBLE_ENCODED, SuspiciousRequestClassifier::isDoubleEncoded);
    this.detectors.put(
        SignalFamily.OVERLONG_URI, request -> request.rawLength() > OVERLONG_URI_LENGTH);
    this.detectors.put(
        SignalFamily.SCANNER_UA, request -> containsAny(request.userAgent(), SCANNER_AGENTS));
  }

  /** The families found in a request with no User-Agent and no browser context. */
  public Set<SignalFamily> classify(
      final String method, final String rawUri, final boolean apiPort) {

    return this.classify(method, rawUri, "", false, apiPort);
  }

  /**
   * The families of suspicious signals in the request, only those switched on; usually none.
   *
   * @param userAgent the {@code User-Agent} header, empty when there is none; the only header read
   *     besides the ones the caller summarises in {@code browserNavigation}
   * @param browserNavigation whether a browser is navigating to the URL (a person typed or followed
   *     it), so a repo named like a script is not a probe
   */
  public Set<SignalFamily> classify(
      final String method,
      final String rawUri,
      final String userAgent,
      final boolean browserNavigation,
      final boolean apiPort) {

    final var uri =
        rawUri.length() > MAX_INSPECTED_LENGTH ? rawUri.substring(0, MAX_INSPECTED_LENGTH) : rawUri;
    final var lower = uri.toLowerCase(Locale.ROOT);
    final var once = decode(lower);
    final var request =
        new Request(
            method,
            lower,
            once,
            decode(once),
            rawUri.length(),
            userAgent.toLowerCase(Locale.ROOT),
            browserNavigation);
    final var found = EnumSet.noneOf(SignalFamily.class);

    for (final var detector : this.detectors.entrySet()) {
      if (this.isOn(detector.getKey(), apiPort) && detector.getValue().test(request)) {
        found.add(detector.getKey());
      }
    }

    return found;
  }

  private boolean isOn(final SignalFamily family, final boolean apiPort) {

    return (apiPort || !this.properties.apiPortOnly(family)) && this.properties.weight(family) > 0;
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

  private static boolean isScriptTarget(final Request request) {

    final var path = pathOf(request.twice());

    // Stored names (a repo called shop.php, a file in a package) travel under /api/ in requests the
    // panel itself makes, so only a request for a script at the root of the server is a probe.
    if (request.browserNavigation() || path.startsWith(API_PREFIX)) {
      return false;
    }
    final var semicolon = path.indexOf(';');
    final var file = semicolon < 0 ? path : path.substring(0, semicolon);

    return SCRIPT_EXTENSIONS.stream().anyMatch(file::endsWith) || containsAny(path, SCRIPT_NEEDLES);
  }

  /** {@code %25} followed by two hex digits: a {@code %} that was itself percent-encoded. */
  private static boolean isDoubleEncoded(final Request request) {

    final var raw = request.lower();

    for (var i = raw.indexOf(ENCODED_PERCENT); i >= 0; i = raw.indexOf(ENCODED_PERCENT, i + 1)) {
      if (hasTwoHexDigitsAt(raw, i + ENCODED_PERCENT.length())) {
        return true;
      }
    }

    return false;
  }

  private static boolean hasTwoHexDigitsAt(final String text, final int index) {

    return index + 1 < text.length()
        && hex(text.charAt(index)) >= 0
        && hex(text.charAt(index + 1)) >= 0;
  }

  private static boolean containsAny(final String text, final List<String> needles) {

    return needles.stream().anyMatch(text::contains);
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
  private record Request(
      String method,
      String lower,
      String once,
      String twice,
      int rawLength,
      String userAgent,
      boolean browserNavigation) {

    /** Path and query, decoded twice, with {@code +} read as a space in the query. */
    String pathAndQuery() {

      return pathOf(this.twice()) + " " + this.query();
    }

    /** The query only (Maven's {@code ${project.version}} is a real path, so not for CMDI). */
    String query() {

      final var question = this.twice().indexOf('?');

      return question < 0 ? "" : this.twice().substring(question + 1).replace('+', ' ');
    }

    /** Everything the request carried in its URI, in each of its three forms. */
    String everything() {

      return this.lower() + " " + this.once() + " " + this.twice();
    }
  }

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
