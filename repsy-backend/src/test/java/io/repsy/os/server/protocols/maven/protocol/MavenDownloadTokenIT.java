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
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.auth0.jwt.JWT;
import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.shared.auth.utils.AuthUtils;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

/**
 * RPS-980: the Maven browser downloads a file by navigating to it, which cannot set an {@code
 * Authorization} header. It used to put the session's panel access token in {@code ?token=}; it now
 * asks the panel API for a token that opens one path for a minute and puts that in {@code
 * ?downloadToken=}. These tests pin the token's scope and that a panel token no longer works there.
 */
@DisplayName("Maven download token")
class MavenDownloadTokenIT extends AbstractIntegrationTest {

  private static final String JAR = "com/example/lib/1.0/lib-1.0.jar";
  private static final String OTHER_JAR = "com/example/lib/1.0/lib-1.0-sources.jar";
  private static final String JAR_CONTENT = "jar-bytes";

  private MvcResult protocol(final AbstractMockHttpServletRequestBuilder<?> request)
      throws Exception {
    return this.mockMvc.perform(request.with(protocolPort())).andReturn();
  }

  private Repo seedMavenWithFiles(final boolean privateRepo) throws IOException {
    final var repo = this.seedRepo(RepoType.MAVEN, uniqueRepoName("dl-mvn"), privateRepo, null);

    for (final var path : new String[] {JAR, OTHER_JAR}) {
      final var file = storageDirOf(repo).resolve(path);
      Files.createDirectories(file.getParent());
      Files.writeString(file, path.equals(JAR) ? JAR_CONTENT : "sources-bytes");
    }

    return repo;
  }

  private String issue(final Repo repo, final String path) throws Exception {
    final var body =
        this.perform(
                post("/api/repos/{repo}/download-token", repo.getName())
                    .param("path", "/" + path)
                    .header(AUTHORIZATION, this.userBearerToken()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.msgId").value("downloadTokenCreated"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    return JsonPath.read(body, "$.data");
  }

  private static AbstractMockHttpServletRequestBuilder<?> download(
      final Repo repo, final String path, final String downloadToken) {
    return get("/{repo}/{path}", repo.getName(), path).param("downloadToken", downloadToken);
  }

  @Nested
  @DisplayName("POST /api/repos/{repoName}/download-token")
  class Issuing {

    @Test
    @DisplayName("issues a token that lives about a minute and carries neither a user nor a scope")
    void issuesAShortLivedToken() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var token = issue(repo, JAR);
      final var decoded = JWT.decode(token);

      assertThat(decoded.getAudience()).containsExactly("download");
      assertThat(decoded.getSubject()).isEqualTo(repo.getId().toString());
      assertThat(decoded.getClaim("path").asString()).isEqualTo("/" + JAR);
      assertThat(decoded.getClaim("username").isMissing()).isTrue();
      assertThat(decoded.getExpiresAtAsInstant())
          .isBetween(Instant.now().plusSeconds(30), Instant.now().plusSeconds(65));
    }

    @Test
    @DisplayName("rejects a caller without a panel session")
    void requiresAPanelSession() throws Exception {
      final var repo = seedMavenWithFiles(true);
      final var user = createUser(uniqueUsername("anon"), UserRole.USER);

      perform(post("/api/repos/{repo}/download-token", repo.getName()).param("path", "/" + JAR))
          .andExpect(status().isUnauthorized());

      perform(
              post("/api/repos/{repo}/download-token", repo.getName())
                  .param("path", "/" + JAR)
                  .header(AUTHORIZATION, protocolBearerTokenFor(user)))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("is only available for Maven repositories")
    void mavenOnly() throws Exception {
      final var npm = seedRepo(RepoType.NPM, uniqueRepoName("dl-npm"), true, null);

      perform(
              post("/api/repos/{repo}/download-token", npm.getName())
                  .param("path", "/some-package")
                  .header(AUTHORIZATION, userBearerToken()))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.msgId").value("repoScopeNotMatched"));
    }

    @Test
    @DisplayName("answers repoNotFound for a repo that does not exist")
    void unknownRepo() throws Exception {
      perform(
              post("/api/repos/{repo}/download-token", uniqueRepoName("ghost"))
                  .param("path", "/" + JAR)
                  .header(AUTHORIZATION, userBearerToken()))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.msgId").value("repoNotFound"));
    }

    @Test
    @DisplayName("refuses a path that leaves the repo")
    void refusesTraversal() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result =
          perform(
                  post("/api/repos/{repo}/download-token", repo.getName())
                      .param("path", "/com/../../other-repo/secret.txt")
                      .header(AUTHORIZATION, userBearerToken()))
              .andReturn();

      assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
      assertThat(result.getResponse().getContentAsString()).contains("invalidStoragePath");
    }
  }

  @Nested
  @DisplayName("downloading with ?downloadToken=")
  class Downloading {

    @Test
    @DisplayName("a token downloads the file it was issued for from a private repo")
    void validToken() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result = protocol(download(repo, JAR, issue(repo, JAR)));

      assertThat(result.getResponse().getStatus()).isEqualTo(200);
      assertThat(result.getResponse().getContentAsString()).isEqualTo(JAR_CONTENT);
    }

    @Test
    @DisplayName("without a token a private repo still answers 401")
    void privateRepoNeedsCredentials() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result = protocol(get("/{repo}/{path}", repo.getName(), JAR));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("an expired token is refused with downloadTokenExpired")
    void expiredToken() throws Exception {
      final var repo = seedMavenWithFiles(true);
      final var expired =
          jwtUtils.createDownloadToken(repo.getId(), "/" + JAR, Duration.ofSeconds(-1));

      final var result = protocol(download(repo, JAR, expired));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).contains("downloadTokenExpired");
    }

    @Test
    @DisplayName("a token for another path of the same repo is refused")
    void wrongPath() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result = protocol(download(repo, OTHER_JAR, issue(repo, JAR)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).contains("accessNotAllowed");
    }

    @Test
    @DisplayName("a token for a parent directory does not open the files below it")
    void parentDirectoryToken() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result = protocol(download(repo, JAR, issue(repo, "com/example/lib/1.0")));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a token for another repo is refused, even for the same path")
    void wrongRepo() throws Exception {
      final var repo = seedMavenWithFiles(true);
      final var other = seedMavenWithFiles(true);

      final var result = protocol(download(other, JAR, issue(repo, JAR)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getContentAsString()).contains("accessNotAllowed");
    }

    @Test
    @DisplayName("a token does not authorize a write")
    void readOnly() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result =
          protocol(
              put("/{repo}/{path}", repo.getName(), JAR)
                  .param("downloadToken", issue(repo, JAR))
                  .content("overwritten"));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(Files.readString(storageDirOf(repo).resolve(JAR))).isEqualTo(JAR_CONTENT);
    }

    @Test
    @DisplayName("a forged token is refused")
    void forgedToken() throws Exception {
      final var repo = seedMavenWithFiles(true);

      final var result = protocol(download(repo, JAR, "not.a.token"));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the other protocols do not know the parameter")
    void otherProtocolsIgnoreIt() throws Exception {
      final var npm = seedRepo(RepoType.NPM, uniqueRepoName("dl-npm"), true, null);
      final var maven = seedMavenWithFiles(true);

      final var result =
          protocol(
              get("/{repo}/some-package", npm.getName()).param("downloadToken", issue(maven, JAR)));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
  }

  @Nested
  @DisplayName("what a download token is not")
  class Scope {

    @Test
    @DisplayName("it is not a panel token: the panel API refuses it")
    void refusedByThePanelApi() throws Exception {
      final var repo = seedMavenWithFiles(true);
      final var bearer = AuthUtils.AUTH_BEARER + issue(repo, JAR);

      perform(get("/api/profile").header(AUTHORIZATION, bearer))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));

      perform(
              get("/api/repos/{repo}/contents", repo.getName())
                  .param("path", "/")
                  .header(AUTHORIZATION, bearer))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));

      perform(
              post("/api/repos/{repo}/download-token", repo.getName())
                  .param("path", "/" + JAR)
                  .header(AUTHORIZATION, bearer))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.msgId").value("accessNotAllowed"));
    }

    @Test
    @DisplayName("it is not a protocol token: Maven refuses it in the header and in ?token=")
    void refusedAsAProtocolToken() throws Exception {
      final var repo = seedMavenWithFiles(true);
      final var token = issue(repo, JAR);

      final var inHeader =
          protocol(
              get("/{repo}/{path}", repo.getName(), JAR)
                  .header(AUTHORIZATION, AuthUtils.AUTH_BEARER + token));
      final var inQuery =
          protocol(get("/{repo}/{path}", repo.getName(), JAR).param("token", token));

      assertThat(inHeader.getResponse().getStatus()).isEqualTo(401);
      assertThat(inQuery.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("a panel access token in ?token= is not a credential, so Maven asks for one")
    void panelTokenInQueryIsRefused() throws Exception {
      final var user = createUser(uniqueUsername("panel"), UserRole.USER);
      final var repo = seedMavenWithFiles(true);
      final var panelToken = bearerTokenFor(user).substring(AuthUtils.AUTH_BEARER.length());

      final var result =
          protocol(get("/{repo}/{path}", repo.getName(), JAR).param("token", panelToken));

      assertThat(result.getResponse().getStatus()).isEqualTo(401);
      assertThat(result.getResponse().getHeader(WWW_AUTHENTICATE))
          .isEqualTo("Basic realm=\"Repsy Managed Repository\"");
    }
  }
}
