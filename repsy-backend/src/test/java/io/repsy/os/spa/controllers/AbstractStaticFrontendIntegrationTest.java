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
package io.repsy.os.spa.controllers;

import io.repsy.os.AbstractIntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An integration test whose {@code spring.web.resources.static-locations} points at a temporary
 * directory that stands in for the built Angular panel, which this backend-only build does not
 * produce: an {@code index.html} for {@code SpaController}'s {@code forward:/index.html} to
 * resolve, a hashed script and stylesheet at the root, and a font, a script and an image under
 * {@code assets/}, the layout of the real build ({@code outputPath.media} is {@code assets}).
 *
 * <p>The directory sits inside another temporary directory that holds {@link #SECRET_FILE}, one
 * level above the static root, so a path traversal test can tell a leak from a refusal.
 *
 * <p>A different static location is a different Spring context, and the number of contexts is
 * capped ({@code ContextCountGuard}), so the classes that need it extend this one and share a
 * single context instead of each declaring a {@code @DynamicPropertySource} of its own.
 */
public abstract class AbstractStaticFrontendIntegrationTest extends AbstractIntegrationTest {

  /**
   * What {@code assets/} and the root hold; a test reads them back through the resource handler.
   */
  static final String INDEX_HTML = "<!doctype html><html><body>panel</body></html>";

  static final String FONT_BYTES = "wOF2-test-font";
  static final String SECRET = "TOP-SECRET-OUTSIDE-THE-STATIC-ROOT";

  private static final Path STATIC_ROOT;

  static {
    try {
      final var outer = Files.createTempDirectory("repsy-it-frontend");
      Files.writeString(outer.resolve("secret.txt"), SECRET, StandardCharsets.UTF_8);
      STATIC_ROOT = Files.createDirectory(outer.resolve("static"));
      Files.writeString(STATIC_ROOT.resolve("index.html"), INDEX_HTML, StandardCharsets.UTF_8);
      Files.writeString(
          STATIC_ROOT.resolve("main-ABC123.js"), "console.log('panel');", StandardCharsets.UTF_8);
      Files.writeString(
          STATIC_ROOT.resolve("styles-XYZ789.css"), "body{margin:0}", StandardCharsets.UTF_8);
      final var assets = Files.createDirectory(STATIC_ROOT.resolve("assets"));
      Files.writeString(assets.resolve("remixicon-A1B2C3.woff2"), FONT_BYTES);
      Files.writeString(assets.resolve("chunk-Q1W2E3.js"), "export default 1;");
      Files.writeString(assets.resolve("logo.svg"), "<svg xmlns='http://www.w3.org/2000/svg'/>");
      // A file the build could put under a directory other than assets/ (RPS-1445 had it under
      // media/): the SPA controller answers before the resource handler, so it is not served.
      Files.writeString(
          Files.createDirectory(STATIC_ROOT.resolve("media")).resolve("stray.woff2"), FONT_BYTES);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void registerStaticLocation(final DynamicPropertyRegistry registry) {
    registry.add("spring.web.resources.static-locations", () -> "file:" + STATIC_ROOT + "/");
  }
}
