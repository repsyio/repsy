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

import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.OCI_INDEX;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.bytes;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.imageManifest;
import static io.repsy.os.server.protocols.docker.protocol.handlers.DockerWire.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * The OCI 1.1 referrers API ({@code GET /v2/<repo>/<image>/referrers/<digest>}) is no route of the
 * Docker server: it answers {@code 404 NAME_UNKNOWN / unknownPath} to every caller, before
 * authentication. Clients that need referrers ({@code oras discover}, {@code regctl artifact tree},
 * {@code cosign tree}) take the spec-mandated fallback, the referrers tag schema: an ordinary tag
 * {@code sha256-<hex>} that holds an image index. The tests pin the missing route at its current
 * behaviour and prove the fallback tag works like any other tag.
 */
@DisplayName("Docker referrers API is no route, the tag-schema fallback works")
class DockerReferrersIT extends AbstractIT {

  private static final String IMAGE = "app";

  @Autowired private WebApplicationContext webApplicationContext;
  @MockitoBean private UsageUpdateService usageUpdateService;

  private String adminToken;
  private DockerWire wire;
  private Repo repo;
  private String manifestDigest;

  @BeforeEach
  void pushAnImage() throws Exception {
    this.adminToken = this.adminProtocolBearerToken();
    this.wire =
        new DockerWire(this.mockMvc, this.webApplicationContext, protocolPort(), this.adminToken);
    this.repo = this.seedRepo(RepoType.DOCKER, uniqueRepoName("docker"), true, null);
    this.wire.pushBlobsOf(this.repo, IMAGE, "layer");
    final var manifest = imageManifest("layer");

    assertThat(this.wire.putImage(this.repo, IMAGE, "v1", manifest).getStatus()).isEqualTo(201);
    this.manifestDigest = sha256(bytes(manifest));
  }

  private MockHttpServletResponse referrers(final String digest, final String query)
      throws Exception {
    return this.mockMvc
        .perform(
            get("/v2/{repo}/{image}/referrers/{digest}" + query, this.repo.getName(), IMAGE, digest)
                .header(AUTHORIZATION, this.adminToken)
                .with(protocolPort()))
        .andReturn()
        .getResponse();
  }

  private static void assertNoRoute(final MockHttpServletResponse response) throws Exception {
    final var body = response.getContentAsString();

    assertThat(response.getStatus()).as(body).isEqualTo(404);
    assertThat(JsonPath.<String>read(body, "$.errors[0].code")).isEqualTo("NAME_UNKNOWN");
    assertThat(JsonPath.<String>read(body, "$.errors[0].detail")).isEqualTo("unknownPath");
    assertThat(response.getHeader(WWW_AUTHENTICATE)).isNull();
  }

  @Test
  @DisplayName("referrers of a stored manifest, with or without artifactType, is 404 unknownPath")
  void referrersOfAStoredManifestIsNoRoute() throws Exception {
    assertNoRoute(this.referrers(this.manifestDigest, ""));
    assertNoRoute(this.referrers(this.manifestDigest, "?artifactType=application/vnd.e2e.test"));
  }

  @Test
  @DisplayName("referrers of an unknown digest is the same 404, not the spec's empty index")
  void referrersOfAnUnknownDigestIsNoRoute() throws Exception {
    assertNoRoute(this.referrers("sha256:" + "0".repeat(64), ""));
  }

  @Test
  @DisplayName("an anonymous caller gets the same 404 and no challenge")
  void anonymousReferrersIsNoRoute() throws Exception {
    final var response =
        this.mockMvc
            .perform(
                get(
                        "/v2/{repo}/{image}/referrers/{digest}",
                        this.repo.getName(),
                        IMAGE,
                        this.manifestDigest)
                    .with(protocolPort()))
            .andReturn()
            .getResponse();

    assertNoRoute(response);
  }

  @Test
  @DisplayName("the fallback tag is absent until written, then an ordinary tag holding the index")
  void tagSchemaFallbackWorksAsAnOrdinaryTag() throws Exception {
    final var fallbackTag = this.manifestDigest.replace(':', '-');

    final var missing = this.wire.getManifest(this.repo, IMAGE, fallbackTag);
    assertThat(missing.getStatus()).as(missing.getContentAsString()).isEqualTo(404);
    assertThat(JsonPath.<String>read(missing.getContentAsString(), "$.errors[0].code"))
        .isEqualTo("MANIFEST_UNKNOWN");
    assertThat(JsonPath.<String>read(missing.getContentAsString(), "$.errors[0].detail"))
        .isEqualTo("tagNotFound");

    final var index =
        "{\"schemaVersion\":2,\"mediaType\":\"%s\",\"manifests\":[]}".formatted(OCI_INDEX);
    final var put = this.wire.putManifest(this.repo, IMAGE, fallbackTag, OCI_INDEX, index);
    assertThat(put.getStatus()).as(put.getContentAsString()).isEqualTo(201);

    final var read = this.wire.getManifest(this.repo, IMAGE, fallbackTag);
    assertThat(read.getStatus()).isEqualTo(200);
    assertThat(read.getHeader(CONTENT_TYPE)).startsWith(OCI_INDEX);
    assertThat(read.getContentAsString()).isEqualTo(index);
  }
}
