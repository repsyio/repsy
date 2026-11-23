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
package io.repsy.os.server.protocols.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import io.repsy.os.AbstractIT;
import io.repsy.os.server.shared.token.entities.RepoDeployToken;
import io.repsy.os.server.shared.token.repositories.RepoDeployTokenRepository;
import io.repsy.os.server.shared.token.utils.TokenUsernameGenerator;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import io.repsy.protocols.shared.token.DeployTokenHash;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * RPS-2739 (the IT twin of the e2e {@code no-route-manage} matrix, RPS-1475): PyPI, Go, Maven and
 * Helm's OCI protocol have no wire request that removes anything (twine, the GOPROXY protocol,
 * {@code mvn deploy} and {@code helm push} only write). The router answers a {@code DELETE} no
 * handler recognises with 404 {@code unknownPath} before any credential is looked at, so all five
 * credentials (an admin, a USER account, a read-write and a read-only deploy token, and no
 * credential) get the same 404, whether the file exists or not, and the file stays.
 */
@DisplayName("DELETE on a protocol path that has no delete route (RPS-2739)")
// extended: protocol permission matrix, every credential on every protocol
@Tag("extended")
class ProtocolPathDeleteIT extends AbstractIT {

  private static final String CONTENT = "sweep-bytes";

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoDeployTokenRepository deployTokenRepository;

  static Stream<Arguments> routes() {
    return Stream.of(
        Arguments.of("maven", RepoType.MAVEN, "com/example/lib/1.0/lib-1.0.jar"),
        Arguments.of("pypi", RepoType.PYPI, "demo/-/demo-1.0.0-py3-none-any.whl"),
        Arguments.of("go", RepoType.GOLANG, "example.com/mod/@v/v1.0.0.zip"),
        Arguments.of("helm oci manifest", RepoType.HELM, "v2/{repo}/payments/manifests/1.0.0"));
  }

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

  private Map<String, Supplier<String>> credentials(final Repo repo) {
    final var user = this.createUser(uniqueUsername("rps2739"), UserRole.USER);
    final var credentials = new LinkedHashMap<String, Supplier<String>>();

    credentials.put("an admin", this::adminProtocolBearerToken);
    credentials.put("a USER account", () -> basicAuth(user.getUsername(), VALID_PASSWORD));
    credentials.put(
        "a read-write deploy token", () -> basicAuth("deploy", this.seedToken(repo, false)));
    credentials.put(
        "a read-only deploy token", () -> basicAuth("deploy", this.seedToken(repo, true)));
    credentials.put("no credential", () -> null);

    return credentials;
  }

  private MockHttpServletResponse deleteAs(
      final Repo repo, final String path, final String authorization) throws Exception {
    final var builder =
        delete("/{repo}/{path}", repo.getName(), path.replace("{repo}", repo.getName()));

    if (authorization != null) {
      builder.header(AUTHORIZATION, authorization);
    }

    return this.mockMvc.perform(builder.with(protocolPort())).andReturn().getResponse();
  }

  @ParameterizedTest(name = "{0}: every credential gets 404 unknownPath and the file stays")
  @MethodSource("routes")
  void noCredentialDeletes(final String protocol, final RepoType type, final String path)
      throws Exception {
    final var repo =
        this.seedRepo(type, uniqueRepoName("nr-" + type.name().toLowerCase()), true, null);
    final var file = storageDirOf(repo).resolve(path.replace("{repo}", repo.getName()));

    Files.createDirectories(file.getParent());
    Files.writeString(file, CONTENT);

    for (final var credential : this.credentials(repo).entrySet()) {
      final var response = this.deleteAs(repo, path, credential.getValue().get());

      assertThat(response.getStatus()).as("%s as %s", protocol, credential.getKey()).isEqualTo(404);
      assertThat(response.getContentAsString())
          .as("%s as %s", protocol, credential.getKey())
          .contains("\"msgId\":\"unknownPath\"");
      assertThat(readOrNull(file)).as("the file after %s", credential.getKey()).isEqualTo(CONTENT);
    }
  }

  private static String readOrNull(final java.nio.file.Path file) throws IOException {
    return Files.exists(file) ? Files.readString(file) : null;
  }
}
