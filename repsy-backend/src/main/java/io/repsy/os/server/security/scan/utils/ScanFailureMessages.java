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
package io.repsy.os.server.security.scan.utils;

import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Turns the reason a scan failed into a text that is safe to store and to show in the panel.
 *
 * <p>The reason comes from three places: messages this backend writes itself (curated), the
 * scanner's own {@code errorMessage} and the message of whatever exception the scan hit. The last
 * two are not under our control and can carry what should stay private: the scanner's URL or an IP
 * address inside the cluster, the path of a temporary or storage file, a multi-line stack trace or
 * the scanner's stderr. This keeps the first line only, redacts URLs, addresses, {@code host:port}
 * pairs and file paths, strips control characters and bounds the length. It is idempotent, so it
 * can run both when the message is recorded and when it is read (rows recorded before this existed
 * are cleaned on the way out).
 */
@NullMarked
public final class ScanFailureMessages {

  /** The longest text shown, ellipsis included. */
  public static final int MAX_LENGTH = 200;

  private static final String REDACTED = "[redacted]";
  private static final String ELLIPSIS = "...";

  private static final Pattern URL = Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.-]*://\\S+");
  private static final Pattern IPV4 =
      Pattern.compile("(?:[\\w.-]+/)?\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b(?::\\d{1,5})?");
  private static final Pattern IPV6 =
      Pattern.compile(
          "(?<![\\w:])\\[?(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\]?(?::\\d{1,5})?(?![\\w:])");
  private static final Pattern HOST_PORT = Pattern.compile("\\b[a-zA-Z][\\w.-]*:\\d{2,5}\\b");
  private static final Pattern UNIX_PATH =
      Pattern.compile("(?<![\\w/.:-])/(?:[\\w.@+~=-]+/)*[\\w.@+~=-]+/?");
  private static final Pattern WINDOWS_PATH = Pattern.compile("\\b[a-zA-Z]:\\\\\\S*");
  private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private ScanFailureMessages() {}

  /**
   * The display-safe form of {@code raw}: null when there is nothing to show, otherwise one line of
   * at most {@link #MAX_LENGTH} characters.
   */
  public static @Nullable String sanitize(final @Nullable String raw) {
    if (raw == null) {
      return null;
    }

    final var firstLine = raw.strip().lines().findFirst().orElse("");
    var text = CONTROL.matcher(firstLine).replaceAll(" ");
    text = URL.matcher(text).replaceAll(REDACTED);
    text = IPV4.matcher(text).replaceAll(REDACTED);
    text = IPV6.matcher(text).replaceAll(REDACTED);
    text = HOST_PORT.matcher(text).replaceAll(REDACTED);
    text = WINDOWS_PATH.matcher(text).replaceAll(REDACTED);
    text = UNIX_PATH.matcher(text).replaceAll(REDACTED);
    text = WHITESPACE.matcher(text).replaceAll(" ").strip();

    if (text.isEmpty()) {
      return null;
    }

    return text.length() <= MAX_LENGTH
        ? text
        : text.substring(0, MAX_LENGTH - ELLIPSIS.length()).stripTrailing() + ELLIPSIS;
  }
}
