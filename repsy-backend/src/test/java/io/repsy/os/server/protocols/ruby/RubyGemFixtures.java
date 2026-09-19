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
package io.repsy.os.server.protocols.ruby;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** What the Ruby integration tests share to push gems through the wire protocol. */
public final class RubyGemFixtures {

  /** The main port, where the protocol router serves {@code gem push} and friends. */
  public static final int PROTOCOL_PORT = 9090;

  /** {@code gem push} endpoint of a repo. */
  public static final String PUBLISH_PATH = "/{repo}/api/v1/gems";

  private RubyGemFixtures() {
    throw new UnsupportedOperationException("Utility class");
  }

  /**
   * Serves the request the way the protocol port does: main port, servlet path = request URI.
   *
   * <p>The protocol path parser resolves the repo from {@code request.getServletPath()}. MockMvc
   * leaves that empty unless the test sets it, so a request that skips this post-processor never
   * matches any handler and ends in {@code 404 unknownPath} even though the route is registered.
   */
  public static RequestPostProcessor protocolPort() {
    return request -> {
      request.setLocalPort(PROTOCOL_PORT);
      request.setServletPath(request.getRequestURI());
      return request;
    };
  }

  /** A pure-Ruby gem with the default description. */
  public static byte[] gem(final String name, final String version) throws IOException {
    return gem(name, version, "ruby", "fixture");
  }

  /**
   * Builds the minimal outer tar {@code GemspecParser} needs ({@code metadata.gz} + {@code
   * data.tar.gz}), with representative metadata: two authors, a homepage, a required Ruby version
   * and one runtime and one development dependency.
   */
  public static byte[] gem(
      final String name, final String version, final String platform, final String description)
      throws IOException {
    final var metadata =
        """
        name: %s
        version:
          version: %s
        platform: %s
        description: %s
        authors:
        - Alice
        - Bob
        homepage: https://example.test/%s
        required_ruby_version:
          requirements:
          - - ">="
            - version: 3.1.0
        dependencies:
        - name: rack
          type: runtime
          requirement:
            requirements:
            - - ">="
              - version: 3.0.0
        - name: rake
          type: development
          requirement:
            requirements:
            - - ">="
              - version: 13.0.0
        """
            .formatted(name, version, platform, description, name);
    final var output = new ByteArrayOutputStream();

    try (var tar = new TarArchiveOutputStream(output)) {
      add(tar, "metadata.gz", gzip(metadata.getBytes(StandardCharsets.UTF_8)));
      add(tar, "data.tar.gz", gzip(new byte[0]));
      tar.finish();
    }

    return output.toByteArray();
  }

  private static byte[] gzip(final byte[] bytes) throws IOException {
    final var output = new ByteArrayOutputStream();

    try (var gzip = new GZIPOutputStream(output)) {
      gzip.write(bytes);
    }

    return output.toByteArray();
  }

  private static void add(final TarArchiveOutputStream tar, final String name, final byte[] bytes)
      throws IOException {
    final var entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }
}
