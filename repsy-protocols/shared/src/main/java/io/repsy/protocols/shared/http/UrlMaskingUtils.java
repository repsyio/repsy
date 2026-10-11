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

import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Makes upstream URLs, and texts that contain them, safe to log (RPS-2174). A proxy repository URL
 * can embed credentials as userinfo ({@code https://user:token@host/...}) or as query parameters,
 * and the messages of HTTP client exceptions repeat the URL they failed on.
 *
 * <p>The masking fails closed: HTTP clients disagree on where the userinfo of a malformed URL ends
 * ({@code user:p@ss@host}, {@code user:to#ken@host}, backslashes), so nothing of the authority is
 * kept that precedes the last {@code @} of the URL, and a URL is reduced to {@code
 * scheme://host[:port]}, followed by {@code /...} when a path, query or fragment was cut. An
 * authority that is not a plain host (and port) becomes {@code scheme://<redacted>}. A path with an
 * {@code @} in it (an npm scope) is shortened too, which is fine for a log.
 *
 * <p>Every control character of the result is replaced by a visible escape so that an exception
 * message cannot forge log lines. The scan is linear in the length of the text.
 */
// A hand-written linear scan (no regular expression, so no backtracking on hostile input): the
// branching is the character classification, which does not split into meaningful methods.
@SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:MagicNumber"})
public final class UrlMaskingUtils {

  private UrlMaskingUtils() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Path segments after which the next segment is a secret (an npm token revoked by {@code DELETE
   * /-/user/token/<token>}, a signed or download link, a coupon code).
   */
  private static final Set<String> SECRET_MARKERS =
      Set.of(
          "token",
          "download-token",
          "secret",
          "secrets",
          "signature",
          "signed",
          "otp",
          "coupon",
          "coupons",
          "unsubscribe");

  private static final String MASKED_SEGMENT = "***";
  private static final int MAX_PATH_LENGTH = 512;
  private static final String SEPARATOR = "://";
  private static final String REDACTED = "<redacted>";
  private static final String CUT = "/...";
  private static final int MAX_HOST_LENGTH = 255;

  /** Returns the text with every URL in it masked and control characters escaped. */
  public static @Nullable String mask(final @Nullable String text) {
    final var masked = maskUrls(text);

    return masked == null ? null : escapeControlCharacters(masked);
  }

  /**
   * A request path made safe to log: every segment that follows a secret-bearing one ({@code
   * /token/<secret>}) is replaced, because the container normalises {@code .}, {@code ..}, empty
   * and {@code ;param} segments before routing and the secret need not be the next literal segment.
   * URLs and control characters are handled as in {@link #mask(String)}, and the result is cut to a
   * sane length. The query string is not part of a path and is never expected here.
   */
  public static @Nullable String maskPath(final @Nullable String path) {
    if (path == null || path.isEmpty()) {
      return path;
    }

    final var segments = path.split("/", -1);

    // Fail closed: the container normalises the path before routing ('.', '..', empty and
    // ';params' segments), so the secret is not always the literal next segment. Everything after
    // a marker is masked.
    boolean afterMarker = false;

    for (int i = 0; i < segments.length; i++) {
      if (afterMarker) {
        segments[i] = MASKED_SEGMENT;
      } else if (SECRET_MARKERS.contains(markerForm(segments[i]))) {
        afterMarker = true;
      }
    }

    final var joined = String.join("/", segments);
    final var cut =
        joined.length() > MAX_PATH_LENGTH ? joined.substring(0, MAX_PATH_LENGTH) : joined;

    return mask(cut);
  }

  /**
   * The form of a path segment a router would compare against a secret marker: without its path
   * parameters ({@code token;x=1}), percent-decoded (twice at most, so a double-encoded marker does
   * not slip through) and lower-cased. The logged path is the raw request URI, while the route that
   * served the request matched the decoded one.
   */
  private static String markerForm(final String segment) {
    var form = segment;
    final int parameters = form.indexOf(';');

    if (parameters >= 0) {
      form = form.substring(0, parameters);
    }

    for (int pass = 0; pass < 2 && form.indexOf('%') >= 0; pass++) {
      form = decodeAsciiPercent(form);
    }

    return form.toLowerCase(Locale.ROOT);
  }

  /** Decodes {@code %HH} sequences that stand for an ASCII character, leaves any other as is. */
  private static String decodeAsciiPercent(final String value) {
    final var out = new StringBuilder(value.length());
    final int length = value.length();

    for (int i = 0; i < length; i++) {
      final char c = value.charAt(i);

      if (c == '%'
          && i + 2 < length
          && isHexDigit(value.charAt(i + 1))
          && isHexDigit(value.charAt(i + 2))) {
        final int decoded = Integer.parseInt(value.substring(i + 1, i + 3), 16);

        if (decoded < 0x80) {
          out.append((char) decoded);
          i += 2;
          continue;
        }
      }

      out.append(c);
    }

    return out.toString();
  }

  /**
   * Returns the text with every URL in it masked; line breaks stay, for a sink that renders
   * multi-line text (the Slack message, with its stack trace).
   */
  public static @Nullable String maskUrls(final @Nullable String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }

    final var out = new StringBuilder(text.length());
    final int length = text.length();
    int copied = 0;
    int from = 0;

    while (true) {
      final int sep = text.indexOf(SEPARATOR, from);

      if (sep < 0) {
        break;
      }

      int scheme = sep;
      while (scheme > copied && isSchemeChar(text.charAt(scheme - 1))) {
        scheme--;
      }
      while (scheme < sep && !isAsciiLetter(text.charAt(scheme))) {
        scheme++;
      }

      if (scheme == sep) {
        from = sep + SEPARATOR.length();
        continue;
      }

      final int start = sep + SEPARATOR.length();
      final int end = regionEnd(text, start, length);

      // The authority starts after the LAST '@' anywhere in the region, whatever character (space,
      // '#', '/', '?', a quote) precedes it: parsers disagree where the userinfo ends.
      int at = -1;
      for (int i = end - 1; i >= start; i--) {
        if (text.charAt(i) == '@') {
          at = i;
          break;
        }
      }

      final int authority = at >= 0 ? at + 1 : start;

      int hostEnd = authority;
      while (hostEnd < end && !isAuthorityDelimiter(text.charAt(hostEnd))) {
        hostEnd++;
      }

      int tokenEnd = hostEnd;
      while (tokenEnd < end && !isTokenEnd(text.charAt(tokenEnd))) {
        tokenEnd++;
      }

      out.append(text, copied, sep).append(SEPARATOR);

      if (isHostAndPort(text, authority, hostEnd)) {
        out.append(text, authority, hostEnd);
      } else {
        out.append(REDACTED);
      }

      if (at >= 0 || tokenEnd > hostEnd) {
        out.append(CUT);
      }

      copied = tokenEnd;
      from = tokenEnd;
    }

    out.append(text, copied, length);

    return out.toString();
  }

  /**
   * The end of the part of the text that may belong to the URL starting at {@code start}: the
   * scheme of the next URL, or the end of the text. Whitespace and quotes do not end it, since a
   * space or quote inside userinfo must not let the part before it escape the masking.
   */
  private static int regionEnd(final String text, final int start, final int length) {
    for (int i = start; i < length; i++) {
      if (text.charAt(i) == ':' && text.startsWith(SEPARATOR, i)) {
        int s = i;

        while (s > start && isSchemeChar(text.charAt(s - 1))) {
          s--;
        }

        return s;
      }
    }

    return length;
  }

  private static boolean isTokenEnd(final char c) {
    return c == '"' || c == '\'' || c == '<' || c == '>' || Character.isWhitespace(c) || c < 0x20;
  }

  private static boolean isAuthorityDelimiter(final char c) {
    return c == '/' || c == '?' || c == '#' || c == '\\' || isTokenEnd(c);
  }

  /** A DNS name, IPv4 address or bracketed IPv6 literal, optionally followed by {@code :port}. */
  private static boolean isHostAndPort(final String text, final int from, final int to) {
    if (from >= to || to - from > MAX_HOST_LENGTH) {
      return false;
    }

    int hostEnd = to;
    int i = from;

    if (text.charAt(i) == '[') {
      final int close = text.indexOf(']', i);

      if (close < 0 || close >= to || close == i + 1) {
        return false;
      }

      for (int j = i + 1; j < close; j++) {
        final char c = text.charAt(j);

        if (!(isHexDigit(c) || c == ':' || c == '.')) {
          return false;
        }
      }

      hostEnd = close + 1;
      i = to;
    } else {
      while (i < to && text.charAt(i) != ':') {
        final char c = text.charAt(i);

        if (!(isAsciiLetter(c) || isDigit(c) || c == '.' || c == '-' || c == '_')) {
          return false;
        }

        i++;
      }

      hostEnd = i;

      if (hostEnd == from) {
        return false;
      }
    }

    if (hostEnd == to) {
      return true;
    }

    if (text.charAt(hostEnd) != ':' || to - hostEnd - 1 > 5 || to - hostEnd == 1) {
      return false;
    }

    for (int j = hostEnd + 1; j < to; j++) {
      if (!isDigit(text.charAt(j))) {
        return false;
      }
    }

    return true;
  }

  private static String escapeControlCharacters(final CharSequence value) {
    final var result = new StringBuilder(value.length());

    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);

      if (c == '\r') {
        result.append("\\r");
      } else if (c == '\n') {
        result.append("\\n");
      } else if (c == '\t') {
        result.append("\\t");
      } else if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || c == 0x2028 || c == 0x2029) {
        result.append(String.format("\\u%04x", (int) c));
      } else {
        result.append(c);
      }
    }

    return result.toString();
  }

  private static boolean isSchemeChar(final char c) {
    return isAsciiLetter(c) || isDigit(c) || c == '+' || c == '.' || c == '-';
  }

  private static boolean isAsciiLetter(final char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  private static boolean isDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isHexDigit(final char c) {
    return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }
}
