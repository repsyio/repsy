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
package io.repsy.os.server.protocols.npm.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Builds the body of an {@code npm publish}: one version and its tarball, base64 encoded. */
final class NpmPublishBodies {

  private NpmPublishBodies() {}

  /**
   * @param packageName The name, with its scope for a scoped package
   * @param extra Fields of the version, such as {@code description} and {@code keywords}
   */
  static byte[] body(
      final ObjectMapper objectMapper,
      final String repoName,
      final String packageName,
      final String version,
      final Map<String, Object> extra) {

    final var tarball = "tarball".getBytes(StandardCharsets.UTF_8);
    final var fileName =
        packageName.substring(packageName.indexOf('/') + 1) + "-" + version + ".tgz";

    final var versionMetadata = new LinkedHashMap<String, Object>(extra);
    versionMetadata.put("name", packageName);
    versionMetadata.put("version", version);
    versionMetadata.put(
        "dist",
        Map.of(
            "tarball", "http://localhost:9090/" + repoName + "/" + packageName + "/-/" + fileName));

    final var body = new LinkedHashMap<String, Object>();
    body.put("_id", packageName);
    body.put("name", packageName);
    body.put("dist-tags", Map.of("latest", version));
    body.put("versions", Map.of(version, versionMetadata));
    body.put(
        "_attachments",
        Map.of(
            fileName,
            Map.of(
                "content_type",
                "application/octet-stream",
                "data",
                Base64.getEncoder().encodeToString(tarball),
                "length",
                tarball.length)));

    return objectMapper.writeValueAsBytes(body);
  }
}
