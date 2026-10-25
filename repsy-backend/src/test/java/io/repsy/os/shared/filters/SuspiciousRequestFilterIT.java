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

import static org.springframework.http.HttpHeaders.RETRY_AFTER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * RPS-2319: a client that sends path traversals and secret-hunting paths is answered 404 for each
 * and, after two of them (5 strikes each, 10 to ban), refused with 429 on every path. The context
 * is the default one: enforce, 10 strikes, so the test needs none of its own.
 *
 * <p>Every test starts with an empty throttle ({@link AbstractIT}); a second client is sent from
 * another address with {@code remoteAddr}.
 */
@DisplayName("Clients that send probes are banned")
class SuspiciousRequestFilterIT extends AbstractIT {

  private static final String CLIENT = "203.0.113.7";
  private static final String OTHER_CLIENT = "203.0.113.8";
  // 5 strikes: a traversal and nothing else.
  private static final String TRAVERSAL = "/api/orders/../../data";
  // A line of the production logs: a traversal and a secret path, 10 strikes.
  private static final String LOGGED_PROBE = "/api/orders/../../proc/self/environ";

  @Test
  @DisplayName("a traversal on the API port is answered 404 and two of them ban the client")
  void traversalsBanTheClient() throws Exception {

    this.mockMvc
        .perform(get(TRAVERSAL).with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isNotFound());
    this.mockMvc
        .perform(get("/api/uploads/../../data").with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isNotFound());

    // The ban reaches every path, a harmless one too, and carries the nosniff header.
    this.mockMvc
        .perform(get("/api/auth/login-options").with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(RETRY_AFTER, "3600"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    this.mockMvc
        .perform(get("/anything").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("another client is not affected by the ban")
  void otherClientIsNotBanned() throws Exception {

    this.mockMvc.perform(get(TRAVERSAL).with(apiPort()).with(remoteAddr(CLIENT)));
    this.mockMvc.perform(get(TRAVERSAL).with(apiPort()).with(remoteAddr(CLIENT)));

    this.mockMvc
        .perform(get("/api/auth/login-options").with(apiPort()).with(remoteAddr(OTHER_CLIENT)))
        .andExpect(status().is(org.hamcrest.Matchers.not(429)));
  }

  @Test
  @DisplayName("a probe as seen in the production logs bans at once")
  void loggedProbeBansAtOnce() throws Exception {

    this.mockMvc
        .perform(get(LOGGED_PROBE).with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isNotFound());

    this.mockMvc
        .perform(get("/api/auth/login-options").with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("one request that is a traversal, a secret path and TRACE bans at once")
  void heavyRequestBansAtOnce() throws Exception {

    this.mockMvc
        .perform(request(HttpMethod.TRACE, "/api/../.env").with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isNotFound());

    this.mockMvc
        .perform(get("/api/auth/login-options").with(apiPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("a traversal on the protocol port counts but is left to the router to answer")
  void protocolPortTraversalCountsButIsNotAnsweredByTheFilter() throws Exception {

    this.mockMvc
        .perform(get("/repo/../x").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().is(org.hamcrest.Matchers.not(429)));
    this.mockMvc
        .perform(get("/repo/../y").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().is(org.hamcrest.Matchers.not(429)));

    this.mockMvc
        .perform(get("/repo/z").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  @DisplayName("a secret path is not counted on the protocol port")
  void secretPathIsNotCountedOnTheProtocolPort() throws Exception {

    for (var i = 0; i < 5; i++) {
      this.mockMvc.perform(get("/repo/dotenv/.env").with(protocolPort()).with(remoteAddr(CLIENT)));
    }

    this.mockMvc
        .perform(get("/repo/z").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().is(org.hamcrest.Matchers.not(429)));
  }

  @Test
  @DisplayName("an npm scoped name with an encoded slash is no signal")
  void encodedSlashIsNoSignal() throws Exception {

    for (var i = 0; i < 12; i++) {
      this.mockMvc.perform(get("/repo/@scope%2fpkg").with(protocolPort()).with(remoteAddr(CLIENT)));
    }

    this.mockMvc
        .perform(get("/repo/z").with(protocolPort()).with(remoteAddr(CLIENT)))
        .andExpect(status().is(org.hamcrest.Matchers.not(429)));
  }
}
