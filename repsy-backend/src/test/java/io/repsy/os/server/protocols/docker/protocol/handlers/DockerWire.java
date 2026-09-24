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
package io.repsy.os.server.protocols.docker.protocol.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.shared.repo.entities.Repo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pushes and pulls Docker images through the real wire protocol for the integration tests of the
 * manifest model: blobs (HEAD first, as a client does), manifests by tag or by digest, and GET/HEAD
 * of a manifest.
 *
 * <p>A manifest is sent through a {@link MockMvc} without the servlet filters: the manifest handler
 * matches the {@code Content-Type} header exactly, and the character-encoding filter would append
 * {@code ;charset=UTF-8} to it, which a real client (and Tomcat) never sends.
 */
final class DockerWire {

  static final String OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json";
  static final String OCI_INDEX = "application/vnd.oci.image.index.v1+json";
  static final String OCI_CONFIG = "application/vnd.oci.image.config.v1+json";
  static final String OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip";

  private static final byte[] CONFIG =
      "{\"architecture\":\"amd64\",\"os\":\"linux\"}".getBytes(StandardCharsets.UTF_8);

  private final MockMvc mockMvc;
  private final WebApplicationContext webApplicationContext;
  private final RequestPostProcessor protocolPort;
  private final String token;

  DockerWire(
      final MockMvc mockMvc,
      final WebApplicationContext webApplicationContext,
      final RequestPostProcessor protocolPort,
      final String token) {

    this.mockMvc = mockMvc;
    this.webApplicationContext = webApplicationContext;
    this.protocolPort = protocolPort;
    this.token = token;
  }

  static String sha256(final byte[] bytes) {
    return digest("sha256", "SHA-256", bytes);
  }

  static String sha512(final byte[] bytes) {
    return digest("sha512", "SHA-512", bytes);
  }

  private static String digest(final String algorithm, final String jcaName, final byte[] bytes) {
    try {
      return algorithm
          + ":"
          + HexFormat.of().formatHex(MessageDigest.getInstance(jcaName).digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static byte[] bytes(final String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** An image manifest that references the shared config blob and a layer made of {@code layer}. */
  static String imageManifest(final String layer) {
    final var layerBytes = bytes(layer);

    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"config\":{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d},\"layers\":[{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d}]}"
        .formatted(
            OCI_MANIFEST,
            OCI_CONFIG,
            sha256(CONFIG),
            CONFIG.length,
            OCI_LAYER,
            sha256(layerBytes),
            layerBytes.length);
  }

  /** An index that lists the given manifests, all as {@code linux/amd64}. */
  static String index(final String... manifests) {
    final var entries = new StringBuilder();

    for (final var manifest : manifests) {
      if (!entries.isEmpty()) {
        entries.append(',');
      }

      entries.append(
          "{\"mediaType\":\"%s\",\"digest\":\"%s\",\"size\":%d,\"platform\":{\"architecture\":\"amd64\",\"os\":\"linux\"}}"
              .formatted(OCI_MANIFEST, sha256(bytes(manifest)), bytes(manifest).length));
    }

    return "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[%s]}"
        .formatted(OCI_INDEX, entries);
  }

  /** Pushes the shared config blob and a layer made of {@code layer}, as {@code docker push}. */
  void pushBlobsOf(final Repo repo, final String image, final String layer) throws Exception {
    this.pushBlob(repo, image, CONFIG);
    this.pushBlob(repo, image, bytes(layer));
  }

  void pushBlob(final Repo repo, final String image, final byte[] blob) throws Exception {
    final var digest = sha256(blob);
    final var exists =
        this.mockMvc
            .perform(
                head("/v2/{repo}/{image}/blobs/{digest}", repo.getName(), image, digest)
                    .header(AUTHORIZATION, this.token)
                    .with(this.protocolPort))
            .andReturn()
            .getResponse();

    if (exists.getStatus() == 200) {
      return;
    }

    final var start =
        this.mockMvc
            .perform(
                post("/v2/{repo}/{image}/blobs/uploads/", repo.getName(), image)
                    .header(AUTHORIZATION, this.token)
                    .with(this.protocolPort))
            .andReturn()
            .getResponse();
    assertThat(start.getStatus()).isEqualTo(202);
    final var location = start.getHeader("Location");
    final var uploadId = location.substring(location.lastIndexOf('/') + 1);

    final var finalize =
        this.mockMvc
            .perform(
                put("/v2/{repo}/{image}/blobs/uploads/{id}", repo.getName(), image, uploadId)
                    .param("digest", digest)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .content(blob)
                    .header(AUTHORIZATION, this.token)
                    .with(this.protocolPort))
            .andReturn()
            .getResponse();
    assertThat(finalize.getStatus()).as(finalize.getContentAsString()).isEqualTo(201);
  }

  MockHttpServletResponse putManifest(
      final Repo repo,
      final String image,
      final String reference,
      final String contentType,
      final String body)
      throws Exception {

    return MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
        .build()
        .perform(
            put("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), image, reference)
                .contentType(contentType)
                .content(bytes(body))
                .header(AUTHORIZATION, this.token)
                .with(this.protocolPort))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse putImage(
      final Repo repo, final String image, final String reference, final String manifest)
      throws Exception {

    return this.putManifest(repo, image, reference, OCI_MANIFEST, manifest);
  }

  MockHttpServletResponse getManifest(final Repo repo, final String image, final String reference)
      throws Exception {

    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), image, reference)
                .header(AUTHORIZATION, this.token)
                .with(this.protocolPort))
        .andReturn()
        .getResponse();
  }

  MockHttpServletResponse headManifest(final Repo repo, final String image, final String reference)
      throws Exception {

    return this.mockMvc
        .perform(
            head("/v2/{repo}/{image}/manifests/{reference}", repo.getName(), image, reference)
                .header(AUTHORIZATION, this.token)
                .with(this.protocolPort))
        .andReturn()
        .getResponse();
  }
}
