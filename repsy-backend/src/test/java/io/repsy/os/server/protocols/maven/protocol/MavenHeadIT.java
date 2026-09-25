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
package io.repsy.os.server.protocols.maven.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CONTENT_DISPOSITION;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-1368: a HEAD on a Maven path is answered like the GET of the same path without the body. It
 * used to be 200 for every path, so Ivy and sbt, which ask with a HEAD whether a file exists before
 * they publish it, refused the first publish of every release.
 */
@DisplayName("Maven HEAD mirrors GET")
class MavenHeadIT extends AbstractIntegrationTest {

  private static final String JAR = "com/example/lib/1.0/lib-1.0.jar";
  private static final String JAR_CONTENT = "jar-bytes-of-some-length";
  private static final String SHA1 = "com/example/lib/1.0/lib-1.0.jar.sha1";
  private static final String SHA1_CONTENT = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
  private static final String MISSING_POM = "com/example/lib/1.0/lib-1.0.pom";

  private MockHttpServletResponse protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn().getResponse();
  }

  private Repo seed(final boolean privateRepo) throws IOException {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("head-mvn"), privateRepo, null);

    write(repo, JAR, JAR_CONTENT);
    write(repo, SHA1, SHA1_CONTENT);

    return repo;
  }

  private void write(final Repo repo, final String path, final String content) throws IOException {
    final var file = storageDirOf(repo).resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  @Test
  @DisplayName("an existing file is 200 with the length and headers of the GET, and no body")
  void existingFile() throws Exception {
    final var repo = seed(false);

    final var head = protocol(head("/{repo}/{path}", repo.getName(), JAR));
    final var get = protocol(get("/{repo}/{path}", repo.getName(), JAR));

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getContentAsByteArray()).isEmpty();
    assertThat(head.getHeader(CONTENT_LENGTH)).isEqualTo(String.valueOf(JAR_CONTENT.length()));
    assertThat(get.getContentAsString()).isEqualTo(JAR_CONTENT);
    assertThat(head.getHeader(CONTENT_LENGTH)).isEqualTo(get.getHeader(CONTENT_LENGTH));
    assertThat(head.getContentType()).isEqualTo(get.getContentType());
    assertThat(head.getHeader(CONTENT_DISPOSITION)).isEqualTo(get.getHeader(CONTENT_DISPOSITION));
    assertThat(head.getHeader(CONTENT_DISPOSITION)).isEqualTo("attachment; filename=lib-1.0.jar");
  }

  @Test
  @DisplayName("a checksum file is 200 with its own length")
  void checksumFile() throws Exception {
    final var repo = seed(false);

    final var head = protocol(head("/{repo}/{path}", repo.getName(), SHA1));

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader(CONTENT_LENGTH)).isEqualTo(String.valueOf(SHA1_CONTENT.length()));
  }

  @Test
  @DisplayName("a file that was never published is 404, like the GET")
  void missingFile() throws Exception {
    final var repo = seed(false);

    final var head = protocol(head("/{repo}/{path}", repo.getName(), MISSING_POM));
    final var get = protocol(get("/{repo}/{path}", repo.getName(), MISSING_POM));

    assertThat(get.getStatus()).isEqualTo(404);
    assertThat(head.getStatus()).isEqualTo(404);
    assertThat(head.getContentAsByteArray()).isEmpty();
  }

  @Test
  @DisplayName("a directory with the slash is 200 text/html, and without it 307 to the slash")
  void directory() throws Exception {
    final var repo = seed(false);
    final var dir = "com/example/lib/1.0";

    final var withSlash = protocol(head("/{repo}/{path}/", repo.getName(), dir));
    final var getWithSlash = protocol(get("/{repo}/{path}/", repo.getName(), dir));

    assertThat(withSlash.getStatus()).isEqualTo(200);
    assertThat(withSlash.getContentAsByteArray()).isEmpty();
    assertThat(withSlash.getContentType()).startsWith("text/html");
    assertThat(withSlash.getHeader(CONTENT_LENGTH))
        .isEqualTo(getWithSlash.getHeader(CONTENT_LENGTH));
    assertThat(getWithSlash.getContentAsString()).contains("lib-1.0.jar");

    final var withoutSlash = protocol(head("/{repo}/{path}", repo.getName(), dir));
    final var getWithoutSlash = protocol(get("/{repo}/{path}", repo.getName(), dir));

    assertThat(getWithoutSlash.getStatus()).isEqualTo(307);
    assertThat(withoutSlash.getStatus()).isEqualTo(307);
    assertThat(withoutSlash.getHeader(LOCATION)).isEqualTo(getWithoutSlash.getHeader(LOCATION));
    assertThat(withoutSlash.getHeader(LOCATION)).endsWith("/" + dir + "/");
  }

  @Test
  @DisplayName("a directory that does not exist is 404")
  void missingDirectory() throws Exception {
    final var repo = seed(false);

    assertThat(protocol(head("/{repo}/com/nope/", repo.getName())).getStatus()).isEqualTo(404);
  }

  @Test
  @DisplayName("a public repo answers an anonymous HEAD, 200 for a file and 404 for a missing one")
  void publicRepoAnonymous() throws Exception {
    final var repo = seed(false);

    assertThat(protocol(head("/{repo}/{path}", repo.getName(), JAR)).getStatus()).isEqualTo(200);
    assertThat(protocol(head("/{repo}/{path}", repo.getName(), MISSING_POM)).getStatus())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("a private repo still challenges a HEAD without credentials, like the GET")
  void privateRepoNeedsCredentials() throws Exception {
    final var repo = seed(true);

    final var head = protocol(head("/{repo}/{path}", repo.getName(), JAR));
    final var get = protocol(get("/{repo}/{path}", repo.getName(), JAR));

    assertThat(head.getStatus()).isEqualTo(401);
    assertThat(head.getHeader(WWW_AUTHENTICATE)).isNotNull();
    assertThat(head.getHeader(WWW_AUTHENTICATE)).isEqualTo(get.getHeader(WWW_AUTHENTICATE));
    assertThat(head.getHeader(CONTENT_TYPE)).isEqualTo(get.getHeader(CONTENT_TYPE));
  }

  @Test
  @DisplayName("a private repo does not tell an anonymous HEAD whether a file exists")
  void privateRepoMissingFileIsAlso401() throws Exception {
    final var repo = seed(true);

    assertThat(protocol(head("/{repo}/{path}", repo.getName(), MISSING_POM)).getStatus())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("a download token for the path opens a HEAD of it, and only of it")
  void downloadToken() throws Exception {
    final var repo = seed(true);
    final var token = downloadTokenFor(repo, JAR);

    final var head =
        protocol(head("/{repo}/{path}", repo.getName(), JAR).param("downloadToken", token));
    final var other =
        protocol(head("/{repo}/{path}", repo.getName(), SHA1).param("downloadToken", token));

    assertThat(head.getStatus()).isEqualTo(200);
    assertThat(head.getHeader(CONTENT_LENGTH)).isEqualTo(String.valueOf(JAR_CONTENT.length()));
    assertThat(other.getStatus()).isEqualTo(401);
  }

  private String downloadTokenFor(final Repo repo, final String path) throws Exception {
    final var body =
        this.perform(
                post("/api/repos/{repo}/download-token", repo.getName())
                    .param("path", "/" + path)
                    .header(AUTHORIZATION, this.userBearerToken()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    return JsonPath.read(body, "$.data");
  }
}
