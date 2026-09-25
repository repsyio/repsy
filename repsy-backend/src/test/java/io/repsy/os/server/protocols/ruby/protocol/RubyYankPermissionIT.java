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
package io.repsy.os.server.protocols.ruby.protocol;

import static io.repsy.os.server.protocols.ruby.RubyGemFixtures.gem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.DeployTokenHash;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * RPS-1317: {@code gem yank} is a WRITE, like Cargo's yank and NuGet's unlist (it only unpublishes
 * a version from the index and keeps the file, RPS-1238). {@code gem} sends the key raw, so the
 * deploy token goes in the {@code Authorization} header without a scheme, exactly as {@code gem
 * yank --key repsy} does. A read-write deploy token and a USER-role account may yank; a read-only
 * token may not.
 */
@DisplayName("Ruby yank permission (RPS-1317)")
class RubyYankPermissionIT extends AbstractIntegrationTest {

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  private Repo repoWithGem(final String gemName) throws Exception {
    final var repo = this.seedRepo(RepoType.RUBY, uniqueRepoName("rps1317"), true, null);
    this.mockMvc
        .perform(
            post("/{repo}/api/v1/gems", repo.getName())
                .with(protocolPort())
                .header(AUTHORIZATION, this.adminProtocolBearerToken())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(gem(gemName, "1.0.0")))
        .andExpect(status().isOk());
    return repo;
  }

  /** Inserts a deploy token for the repo and returns its secret. */
  private String seedToken(final Repo repo, final boolean readOnly) {
    final var secret = TokenFactory.deployToken();
    final var entity = new RepoDeployToken();

    entity.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    entity.setName("token-" + secret.substring(0, 6));
    entity.setUsername(TokenUsernameGenerator.deployTokenUsername());
    entity.setToken(DeployTokenHash.hash(secret));
    entity.setReadOnly(readOnly);
    entity.setExpirationDate(Instant.now().plus(30, ChronoUnit.DAYS));
    entity.setTokenDurationDay(30);
    this.deployTokenRepository.save(entity);
    this.entityManager.flush();

    return secret;
  }

  private int yank(final Repo repo, final String gemName, final String authorization)
      throws Exception {
    return this.mockMvc
        .perform(
            delete("/{repo}/api/v1/gems/yank", repo.getName())
                .with(protocolPort())
                .header(AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("gem_name", gemName)
                .param("version", "1.0.0"))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  @Test
  @DisplayName("a read-write deploy token sent raw, as gem does, may yank")
  void readWriteDeployTokenMayYank() throws Exception {
    final var repo = this.repoWithGem("rw-yank");

    assertThat(this.yank(repo, "rw-yank", this.seedToken(repo, false))).isEqualTo(200);
  }

  @Test
  @DisplayName("a read-only deploy token may not yank, and the gem stays live")
  void readOnlyDeployTokenMayNotYank() throws Exception {
    final var repo = this.repoWithGem("ro-yank");
    final var secret = this.seedToken(repo, true);

    assertThat(this.yank(repo, "ro-yank", secret)).isEqualTo(401);
    // Nothing was yanked: the admin's own yank is the first one, not a "already yanked" 400.
    assertThat(this.yank(repo, "ro-yank", this.adminProtocolBearerToken())).isEqualTo(200);
  }

  @Test
  @DisplayName("a USER-role account with its password may yank")
  void userRolePasswordMayYank() throws Exception {
    final var repo = this.repoWithGem("user-yank");
    final var user = this.createUser(uniqueUsername("rps1317"), UserRole.USER);

    assertThat(this.yank(repo, "user-yank", basicAuth(user.getUsername(), VALID_PASSWORD)))
        .isEqualTo(200);
  }
}
