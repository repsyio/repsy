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
package io.repsy.os.server.shared.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * RPS-1903: what a personal access token may do on a protocol route is the permission of its owner
 * intersected with its scopes. A scope can only narrow: it never gives more than the owner has, and
 * {@code repo:manage} is never implied by another scope and never makes a user an ADMIN.
 *
 * <p>In Repsy OS every signed-in user reads and writes every repo and only an ADMIN manages, so the
 * owner's permission is read and write for a user and read, write and manage for an admin. The
 * routes are Maven's checksum upload (write), a Maven read, and Helm's chart delete (manage). A
 * refused token is answered {@code 401}, as every protocol answers a caller that may not do it.
 */
@DisplayName("A personal access token is limited to its scopes and to what its owner may do")
class PatScopeIntersectionIT extends AbstractPatIntegrationTest {

  private static final String READ_PATH = "/{repo}/com/example/lib/1.0/lib-1.0.pom";
  private static final String WRITE_PATH = "/{repo}/com/example/lib/1.0/lib-1.0.pom.sha1";
  private static final String CHECKSUM = "da39a3ee5e6b4b0d3255bfef95601890afd80709";
  private static final String MANAGE_PATH = "/{repo}/api/charts/some-chart/1.0.0";

  private int read(final Repo repo, final Pat pat) throws Exception {
    return this.status(
        get(READ_PATH, repo.getName()).header(AUTHORIZATION, basicAuth("any", pat.secret())));
  }

  private int write(final Repo repo, final Pat pat) throws Exception {
    return this.status(
        put(WRITE_PATH, repo.getName())
            .header(AUTHORIZATION, basicAuth("any", pat.secret()))
            .content(CHECKSUM));
  }

  private int manage(final Repo repo, final Pat pat) throws Exception {
    return this.status(
        delete(MANAGE_PATH, repo.getName()).header(AUTHORIZATION, basicAuth("any", pat.secret())));
  }

  @ParameterizedTest(name = "{0} owner: a token without a repo scope reads and writes nothing")
  @EnumSource(UserRole.class)
  void aTokenWithoutARepoScopeDoesNothing(final UserRole role) throws Exception {
    final var repo = this.privateRepo(RepoType.MAVEN);
    final var owner = this.createUser(uniqueUsername("noscope"), role);

    // profile:read is implicit, scan:read is not about repos.
    for (final var pat :
        new Pat[] {this.seedPat(owner), this.seedPat(owner, TokenScope.SCAN_READ)}) {
      assertThat(this.read(repo, pat)).isEqualTo(401);
      assertThat(this.write(repo, pat)).isEqualTo(401);
    }
  }

  @Test
  @DisplayName("repo:read reads and does not write, even for an admin")
  void readDoesNotWrite() throws Exception {
    final var repo = this.privateRepo(RepoType.MAVEN);
    final var admin = this.createUser(uniqueUsername("adm"), UserRole.ADMIN);
    final var pat = this.seedPat(admin, TokenScope.REPO_READ);

    assertThat(this.read(repo, pat)).isEqualTo(404);
    assertThat(this.write(repo, pat)).isEqualTo(401);
    assertThat(this.manage(this.privateRepo(RepoType.HELM), pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("repo:write writes and reads, and does not manage, even for an admin")
  void writeReadsAndDoesNotManage() throws Exception {
    final var repo = this.privateRepo(RepoType.MAVEN);
    final var admin = this.createUser(uniqueUsername("adm"), UserRole.ADMIN);
    final var pat = this.seedPat(admin, TokenScope.REPO_WRITE);

    assertThat(this.read(repo, pat)).isEqualTo(404);
    assertThat(this.write(repo, pat)).isEqualTo(200);
    assertThat(this.manage(this.privateRepo(RepoType.HELM), pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("repo:manage on an admin reads, writes and manages")
  void manageOnAnAdminDoesAll() throws Exception {
    final var admin = this.createUser(uniqueUsername("adm"), UserRole.ADMIN);
    final var pat = this.seedPat(admin, TokenScope.REPO_MANAGE);

    assertThat(this.read(this.privateRepo(RepoType.MAVEN), pat)).isEqualTo(404);
    assertThat(this.write(this.privateRepo(RepoType.MAVEN), pat)).isEqualTo(200);
    assertThat(this.manage(this.privateRepo(RepoType.HELM), pat)).isNotEqualTo(401);
  }

  @Test
  @DisplayName("repo:manage does not give MANAGE to a user who is not an admin")
  void manageDoesNotMakeAnAdmin() throws Exception {
    final var user = this.createUser(uniqueUsername("usr"), UserRole.USER);
    final var pat = this.seedPat(user, TokenScope.REPO_MANAGE);

    // It still reads and writes, as every user does.
    assertThat(this.read(this.privateRepo(RepoType.MAVEN), pat)).isEqualTo(404);
    assertThat(this.write(this.privateRepo(RepoType.MAVEN), pat)).isEqualTo(200);
    assertThat(this.manage(this.privateRepo(RepoType.HELM), pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("a token follows its owner's role: demoting the admin takes MANAGE away at once")
  void theRoleIsReadOnEveryRequest() throws Exception {
    final var admin = this.createUser(uniqueUsername("adm"), UserRole.ADMIN);
    final var pat = this.seedPat(admin, TokenScope.REPO_MANAGE);
    final var repo = this.privateRepo(RepoType.HELM);

    assertThat(this.manage(repo, pat)).isNotEqualTo(401);

    final var row = this.userRepository.findById(admin.getId()).orElseThrow();

    row.setRole(UserRole.USER);
    this.userRepository.saveAndFlush(row);

    assertThat(this.manage(repo, pat)).isEqualTo(401);
  }

  @Test
  @DisplayName("scopes that arrive in any order mean the same")
  void scopeOrderDoesNotMatter() throws Exception {
    final var repo = this.privateRepo(RepoType.MAVEN);
    final var owner = this.createUser(uniqueUsername("order"), UserRole.USER);
    final var pat =
        this.seedPat(owner, TokenScope.SCAN_READ, TokenScope.REPO_WRITE, TokenScope.REPO_READ);

    assertThat(this.read(repo, pat)).isEqualTo(404);
    assertThat(this.write(repo, pat)).isEqualTo(200);
  }
}
