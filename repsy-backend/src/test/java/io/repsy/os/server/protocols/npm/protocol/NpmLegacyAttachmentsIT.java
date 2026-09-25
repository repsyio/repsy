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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1390: a packument that an earlier version of the registry stored with the base64 {@code
 * _attachments} of its publish keeps them on disk until something rewrites it. Every change of a
 * packument writes it without them now, so a dist-tag change reclaims the space, and what the
 * packument says about its versions, and the tarballs, stay as they were.
 */
@DisplayName("npm packuments stored with the publish's attachments (RPS-1390)")
class NpmLegacyAttachmentsIT extends AbstractIntegrationTest {

  private static final String DIST_TAG = "/{repo}/-/package/{name}/dist-tags/{tag}";
  private static final String PACKAGE = "left-pad";

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private ObjectMapper objectMapper;

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private void publish(final Repo repo, final String token, final String version) throws Exception {
    final var response =
        this.protocol(
            put("/{repo}/{name}", repo.getName(), PACKAGE)
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    NpmPublishBodies.body(
                        this.objectMapper, repo.getName(), PACKAGE, version, Map.of())));

    assertThat(response.getStatus()).isEqualTo(200);
  }

  private Map<String, Object> stored(final Repo repo) throws IOException {
    return this.objectMapper.readValue(
        Files.readAllBytes(storageDirOf(repo).resolve(PACKAGE).resolve("package.json")),
        new TypeReference<>() {});
  }

  /** Puts back what the registry used to leave in the file: the base64 copy of a publish. */
  private void storeAsAnOldRegistryDid(final Repo repo) throws IOException {
    final var packument = new LinkedHashMap<>(this.stored(repo));
    packument.put(
        "_attachments",
        Map.of(
            PACKAGE + "-1.0.0.tgz",
            Map.of("content_type", "application/octet-stream", "data", "AAAA", "length", 3)));
    Files.write(
        storageDirOf(repo).resolve(PACKAGE).resolve("package.json"),
        this.objectMapper.writeValueAsBytes(packument));

    assertThat(this.stored(repo)).containsKey("_attachments");
  }

  @Test
  @DisplayName("a dist-tag add and remove rewrite an old packument without its attachments")
  void distTagChangesReclaimTheAttachments() throws Exception {
    final var token =
        this.protocolBearerTokenFor(this.createUser(uniqueUsername("legacy"), UserRole.ADMIN));
    final var repo = this.seedRepo(RepoType.NPM, uniqueRepoName("rps1390"), false, null);
    this.publish(repo, token, "1.0.0");
    this.publish(repo, token, "2.0.0");
    final var versions = this.stored(repo).get("versions");
    final var tarball =
        Files.readAllBytes(storageDirOf(repo).resolve(PACKAGE).resolve("left-pad-1.0.0.tgz"));
    this.storeAsAnOldRegistryDid(repo);

    final var add =
        this.protocol(
            put(DIST_TAG, repo.getName(), PACKAGE, "next")
                .header(AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("\"1.0.0\""));

    assertThat(add.getStatus()).isEqualTo(200);
    assertThat(this.stored(repo))
        .doesNotContainKey("_attachments")
        .containsEntry("versions", versions);
    assertThat(this.stored(repo).get("dist-tags"))
        .isEqualTo(Map.of("latest", "2.0.0", "next", "1.0.0"));
    assertThat(storageDirOf(repo).resolve(PACKAGE).resolve("left-pad-1.0.0.tgz"))
        .hasBinaryContent(tarball);

    this.storeAsAnOldRegistryDid(repo);

    final var remove =
        this.protocol(
            delete(DIST_TAG, repo.getName(), PACKAGE, "next").header(AUTHORIZATION, token));

    assertThat(remove.getStatus()).isEqualTo(200);
    assertThat(this.stored(repo))
        .doesNotContainKey("_attachments")
        .containsEntry("versions", versions);
    assertThat(this.stored(repo).get("dist-tags")).isEqualTo(Map.of("latest", "2.0.0"));
  }
}
