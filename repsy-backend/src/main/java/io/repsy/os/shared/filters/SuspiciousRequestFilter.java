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
package io.repsy.os.shared.filters;

import io.repsy.os.shared.configs.ApiPortMatcher;
import io.repsy.os.shared.configs.SecurityHeadersFilter;
import io.repsy.protocols.shared.auth.AuthThrottleMode;
import io.repsy.protocols.shared.auth.ClientKeys;
import io.repsy.protocols.shared.auth.SignalFamily;
import io.repsy.protocols.shared.auth.SuspiciousRequestClassifier;
import io.repsy.protocols.shared.auth.SuspiciousRequestThrottle;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bans the clients that send probes (RPS-2319), on both ports. Scanners fire path traversals such
 * as {@code /api/orders/../../proc/self/environ} and ask for {@code .env}; see {@link
 * SuspiciousRequestThrottle} for how strikes turn into a ban.
 *
 * <p>Per request, before authentication and before any handler:
 *
 * <ol>
 *   <li>An allowlisted address passes untouched.
 *   <li>A banned client gets {@code 429} with {@code Retry-After} and a one-line body, on every
 *       path, without a handler, a database or a log line per request. In {@code observe} it is let
 *       through and counted instead.
 *   <li>Otherwise {@link SuspiciousRequestClassifier} looks at the method and the raw URI; the
 *       signals found are added to the client's strikes.
 *   <li>A path traversal on the API port is answered {@code 404} right here, in every mode but
 *       {@code off}: no route of the panel has a {@code ..} segment, and this keeps the request
 *       from reaching Spring's resource handler, which logs a WARN per probe. On the protocol port
 *       the request goes on, so the router answers as it always did.
 * </ol>
 *
 * <p>Requests a browser sends for another site ({@code Sec-Fetch-Site: cross-site} or {@code
 * same-site}) are never counted, so a third-party page cannot get a visitor banned.
 *
 * <p>Runs after {@link SecurityHeadersFilter}, so a refusal carries {@code nosniff} too. The
 * counterpart in Repsy Cloud is a hand-made copy, like the other filters of this package.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 110)
@Component
@RequiredArgsConstructor
public class SuspiciousRequestFilter extends OncePerRequestFilter {

  private static final String BANNED_BODY = "Too many requests\n";
  private static final String NOT_FOUND_BODY = "Not found\n";
  private static final String SEC_FETCH_SITE_HEADER = "Sec-Fetch-Site";
  private static final String CROSS_SITE = "cross-site";
  private static final String SAME_SITE = "same-site";
  private static final String SEC_FETCH_MODE_HEADER = "Sec-Fetch-Mode";
  private static final String NAVIGATE = "navigate";

  private final ApiPortMatcher apiPortMatcher;
  private final SuspiciousRequestClassifier classifier;
  private final SuspiciousRequestThrottle throttle;

  @Override
  protected void doFilterInternal(
      final HttpServletRequest request,
      final HttpServletResponse response,
      final FilterChain filterChain)
      throws ServletException, IOException {

    final var remoteAddr = request.getRemoteAddr();

    if (!this.throttle.isActive() || this.throttle.isAllowlisted(remoteAddr)) {
      filterChain.doFilter(request, response);
      return;
    }

    final var client = ClientKeys.throttleKey(remoteAddr);

    if (this.refusesBanned(client, response)) {
      return;
    }

    final var apiPort = this.apiPortMatcher.isApiPort(request.getLocalPort());
    final var signals = this.countSignals(request, client, apiPort);

    if (apiPort && signals.contains(SignalFamily.TRAVERSAL)) {
      this.write(response, HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_BODY);
      return;
    }

    filterChain.doFilter(request, response);
  }

  /** Refuses a banned client with 429 in {@code enforce}; in {@code observe} only counts it. */
  private boolean refusesBanned(final String client, final HttpServletResponse response)
      throws IOException {

    final var banSecondsLeft = this.throttle.banSecondsLeft(client);

    if (banSecondsLeft <= 0) {
      return false;
    }

    if (this.throttle.mode() != AuthThrottleMode.ENFORCE) {
      this.throttle.countWouldBlock(client, banSecondsLeft);
      return false;
    }

    response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(banSecondsLeft));
    this.write(response, HttpStatus.TOO_MANY_REQUESTS.value(), BANNED_BODY);

    return true;
  }

  private Set<SignalFamily> countSignals(
      final HttpServletRequest request, final String client, final boolean apiPort) {

    final var signals =
        this.classifier.classify(
            request.getMethod(),
            requestTarget(request),
            Objects.requireNonNullElse(request.getHeader(HttpHeaders.USER_AGENT), ""),
            NAVIGATE.equalsIgnoreCase(request.getHeader(SEC_FETCH_MODE_HEADER)),
            apiPort);

    if (!signals.isEmpty() && !isCrossSiteBrowserRequest(request)) {
      this.throttle.record(client, request.getRemoteAddr(), signals);
    }

    return signals;
  }

  /** The raw URI with its query, as sent: the query is where injection payloads travel. */
  private static String requestTarget(final HttpServletRequest request) {

    final var query = request.getQueryString();

    return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
  }

  /**
   * Whether a browser sent the request on behalf of another site. A page of any site can make a
   * visitor's browser request {@code /.env} or a {@code %00} path of this server (an image, a
   * script), so counting those would let a third party get the visitor's address, maybe a whole
   * NAT, banned. Browsers mark such requests {@code Sec-Fetch-Site: cross-site} or {@code
   * same-site} and a page cannot change the header; scanners and package managers send none. A
   * scanner that fakes it only dodges the ban: the request is still answered as before.
   */
  private static boolean isCrossSiteBrowserRequest(final HttpServletRequest request) {

    final var site = request.getHeader(SEC_FETCH_SITE_HEADER);

    return CROSS_SITE.equalsIgnoreCase(site) || SAME_SITE.equalsIgnoreCase(site);
  }

  private void write(final HttpServletResponse response, final int status, final String body)
      throws IOException {

    response.setStatus(status);
    response.setContentType("text/plain;charset=UTF-8");
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }
}
