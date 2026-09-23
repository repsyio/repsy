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
package io.repsy.os.shared.configs;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * RPS-1131: {@link SecurityHeadersFilter} pins a {@code Content-Security-Policy} header on the
 * SPA-forwarding and static responses, both on {@code GET /} and on a deep SPA route, and leaves
 * the JSON API alone.
 *
 * <p>{@code spring.web.resources.static-locations} normally points at the built frontend, which
 * this backend-only build does not produce, so this class points it at a temporary directory with a
 * minimal {@code index.html} instead, giving {@code SpaController}'s {@code forward:/index.html}
 * something to actually resolve. That is a different context configuration than every other IT
 * class shares, so it boots a Spring context of its own.
 */
@DisplayName("Content-Security-Policy header (RPS-1131)")
class SecurityHeadersIT extends AbstractIntegrationTest {

  private static final Path STATIC_ROOT;

  static {
    try {
      STATIC_ROOT = Files.createTempDirectory("repsy-it-static");
      Files.writeString(
          STATIC_ROOT.resolve("index.html"),
          "<!doctype html><html><body>panel</body></html>",
          StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void registerStaticLocation(final DynamicPropertyRegistry registry) {
    registry.add("spring.web.resources.static-locations", () -> "file:" + STATIC_ROOT + "/");
  }

  @Test
  @DisplayName("is present on GET /")
  void sendsPolicyOnRoot() throws Exception {

    this.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
        .andExpect(
            header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
  }

  @Test
  @DisplayName("is present on a deep SPA route")
  void sendsPolicyOnDeepSpaRoute() throws Exception {

    this.perform(get("/repos/some-repo/overview"))
        .andExpect(status().isOk())
        .andExpect(header().exists("Content-Security-Policy"));
  }

  @Test
  @DisplayName("is not sent for a JSON API response")
  void doesNotSendPolicyOnJsonApi() throws Exception {

    this.perform(get("/api/profile")).andExpect(header().doesNotExist("Content-Security-Policy"));
  }
}
