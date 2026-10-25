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

import java.util.Locale;

/**
 * The kinds of suspicious request {@link SuspiciousRequestClassifier} recognises (RPS-2319). Each
 * family can be switched off and weighted under {@code
 * repsy.security.probe-throttle.signals.<key>}.
 */
public enum SignalFamily {

  /** A {@code ..} path segment, raw or percent-encoded once or twice. No Repsy route has one. */
  TRAVERSAL(5, false),

  /**
   * A path that only a secret hunter asks for: {@code .env}, {@code /proc/self/}, {@code .git/}.
   */
  SECRET_PATH(5, true),

  /** A NUL byte in the request path. */
  NULL_BYTE(3, false),

  /** A method no Repsy route accepts, such as {@code TRACE} or {@code PROPFIND}. */
  METHOD_ABUSE(3, true),

  /** A request for a script no Repsy server has: {@code .php}, {@code wp-login}, phpMyAdmin. */
  SCRIPT_TARGET(3, true),

  /** A multi-word or function form of SQL injection: {@code union select}, {@code sleep(}. */
  SQLI(2, true),

  /** Markup or a script URL in the path or query: {@code <script}, {@code onerror=}. */
  XSS(2, true),

  /** A shell command in the query: {@code $(}, a backtick, {@code /bin/sh}. */
  CMDI(2, true),

  /** A query value that points the server at cloud metadata or a {@code file://} URL. */
  SSRF_URL(3, true),

  /** A JNDI lookup ({@code ${jndi:}}), the Log4Shell shape. Never legitimate, on any port. */
  LOG4SHELL(5, false),

  /** A template expression such as {@code {{7*7}}} or {@code ${7*7}}. */
  SSTI(2, true),

  /** A percent sign that was encoded again ({@code %252e}) to get past a filter. */
  DOUBLE_ENCODED(1, true),

  /** A URI longer than {@value SuspiciousRequestClassifier#OVERLONG_URI_LENGTH} characters. */
  OVERLONG_URI(1, true),

  /**
   * The User-Agent of a well-known scanner. A header is cheap to change and a scanner can send any
   * other, so it never bans a client alone: it only adds to what other signals found.
   */
  SCANNER_UA(3, false);

  private final int defaultWeight;
  private final boolean apiPortOnly;

  SignalFamily(final int defaultWeight, final boolean apiPortOnly) {

    this.defaultWeight = defaultWeight;
    this.apiPortOnly = apiPortOnly;
  }

  /** Whether strikes of this family can ban a client that has no strike of another family. */
  public boolean bansAlone() {

    return this != SCANNER_UA;
  }

  /** The strikes one request of this family counts, unless the settings say otherwise. */
  public int defaultWeight() {

    return this.defaultWeight;
  }

  /**
   * Whether the family is only checked on the API port. The protocol port carries artifact paths
   * and methods of every package format, where these shapes can be legitimate.
   */
  public boolean apiPortOnly() {

    return this.apiPortOnly;
  }

  /** The settings key, the kebab-case name ({@code secret-path}). */
  public String key() {

    return this.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
