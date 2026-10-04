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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.image.services.ImageTxService;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.NpmPackage;
import io.repsy.os.server.protocols.npm.shared.npm_package.entities.PackageVersion;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.NpmPackageRepository;
import io.repsy.os.server.protocols.npm.shared.npm_package.repositories.PackageVersionRepository;
import io.repsy.os.server.protocols.pypi.protocol.facades.PypiProtocolFacadeImpl;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * RPS-1602 (from repsy-cloud's {@code PublicRepoAuthorizationIT}, RPS-1582): a signed-in user who
 * is not allowed to manage a <em>public</em> repo of somebody else may read it and do nothing that
 * needs {@code MANAGE}, on the panel API and on the wire.
 *
 * <p>Repsy OS has no owners and no per-user permission rows: an authenticated user holds READ and
 * WRITE on every repo, and only MANAGE needs the ADMIN role (README, RPS-939; {@code
 * ProtocolAuthService#checkPermission}). So the "stranger" of Repsy Cloud is here a USER-role
 * account, and what it must not do is what {@code ProtocolAuthService#authorizePanelUser} keeps
 * from it: it is answered 403 {@code accessDenied}, and what it tried to remove or change is still
 * there afterwards. What differs from Cloud, on purpose:
 *
 * <ul>
 *   <li>a USER may deploy to a public repo on the wire (WRITE is granted to every account), so that
 *       is pinned as allowed here, where Cloud pins a refusal;
 *   <li>there are no Docker cleanup-policy routes in OS;
 *   <li>the Docker wire protocol is not repeated: its authorization is the pre-processor's, and
 *       {@code ProtocolAuthChallengeIT} and the Docker wire ITs cover it.
 * </ul>
 *
 * <p>The sweep of every route lives in {@code ManageRoutesStatusIT} (which never fires the
 * handler); this class runs the operations against real resources and checks that they survive.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("A USER who may not manage a public repo can read it and nothing more (RPS-1602)")
class PublicRepoAuthorizationIT extends AbstractIntegrationTest {

  private static final byte[] JAR = "not really a jar".getBytes(StandardCharsets.UTF_8);
  private static final String GROUP = "com.example";
  private static final String JAR_PATH = "com/example/demo/1.0/demo-1.0.jar";
  private static final String POM_PATH = "com/example/demo/1.0/demo-1.0.pom";
  private static final String POM =
      """
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>1.0</version>
      </project>
      """;

  /** {@code @Async}, so it cannot see this class's uncommitted rows. */
  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private PypiProtocolFacadeImpl pypiProtocolFacade;
  @Autowired private NpmPackageRepository npmPackageRepository;
  @Autowired private PackageVersionRepository npmPackageVersionRepository;
  @Autowired private ImageTxService imageTxService;
  @Autowired private ImageRepository imageRepository;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes its contents with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  // ---- Panel API: repo

  @Test
  @DisplayName("cannot delete, edit or inspect a public repo, which is unchanged, but can read it")
  void userCannotManageAPublicRepoButCanReadIt() throws Exception {
    final var repo = this.createRepo(RepoType.MAVEN, "mvn", "as created");
    final var user = this.panelToken(UserRole.USER);
    final var base = "/api/repos/" + repo.getName();

    this.perform(delete(base).header(AUTHORIZATION, user)).andExpect(status().isForbidden());
    this.perform(
            patch(base)
                .header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"defaced\"}"))
        .andExpect(status().isForbidden());
    this.perform(get(base + "/settings").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(get(base + "/usage").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());

    final var stored = this.repoRepository.findByName(repo.getName()).orElseThrow();

    assertThat(stored.getDescription()).isEqualTo("as created");

    // reads: the signed-in user and an anonymous caller both get the repo's detail
    this.perform(get(base + "/permissions").header(AUTHORIZATION, user)).andExpect(status().isOk());
    this.perform(get(base + "/permissions")).andExpect(status().isOk());
    this.perform(get(base).header(AUTHORIZATION, user)).andExpect(status().isOk());
    this.perform(get(base)).andExpect(status().isOk());

    // ... and an admin still manages it
    this.perform(get(base + "/settings").header(AUTHORIZATION, this.panelToken(UserRole.ADMIN)))
        .andExpect(status().isOk());
  }

  // ---- Panel API and wire: Maven

  @Test
  @DisplayName("cannot delete a Maven version, artifact or group, which stay, but can read them")
  void userCannotDeleteFromAPublicMavenRepoButCanReadIt() throws Exception {
    final var repo = this.createRepo(RepoType.MAVEN, "mvn", null);
    final var user = this.panelToken(UserRole.USER);
    final var artifacts = "/api/mvn/artifacts/" + repo.getName();

    this.deployDemo(repo, this.basicAuthOf(this.newUser(UserRole.ADMIN)), 200);

    this.perform(delete(artifacts + "/" + GROUP + "/demo").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(artifacts + "/" + GROUP + "/demo/versions/1.0").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(artifacts + "/" + GROUP).header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());

    // the artifact is still stored, and served to anybody
    for (final var authorization : this.callers(user)) {
      this.getAs(artifacts, authorization).andExpect(status().isOk());
    }

    assertThat(this.listedArtifactNames(repo, user)).containsExactly("demo");
    assertThat(this.listedArtifactNames(repo, null)).containsExactly("demo");
    this.perform(get("/api/mvn/groups/" + repo.getName() + "/" + GROUP)).andExpect(status().isOk());
    this.perform(get("/api/mvn/groups/" + repo.getName() + "/" + GROUP).header(AUTHORIZATION, user))
        .andExpect(status().isOk());
    this.mockMvc
        .perform(get("/{repo}/{path}", repo.getName(), JAR_PATH).with(protocolPort()))
        .andExpect(status().isOk());
  }

  /**
   * The Repsy OS model, pinned: WRITE is not MANAGE, so a USER deploys to a public repo (Repsy
   * Cloud refuses this to a user without a permission row). An anonymous caller does not.
   */
  @Test
  @DisplayName(
      "may deploy to a public Maven repo on the wire, as every account may; anonymous may not")
  void userMayDeployToAPublicMavenRepo() throws Exception {
    final var repo = this.createRepo(RepoType.MAVEN, "mvn", null);
    final var user = this.newUser(UserRole.USER);

    this.deployDemo(repo, null, 401);
    this.deployDemo(repo, this.basicAuthOf(user), 200);

    this.mockMvc
        .perform(get("/{repo}/{path}", repo.getName(), JAR_PATH).with(protocolPort()))
        .andExpect(status().isOk());
  }

  // ---- Panel API: PyPI

  @Test
  @DisplayName("cannot delete a PyPI package or release, which stay, but can read them")
  void userCannotDeleteFromAPublicPypiRepoButCanReadIt() throws Exception {
    final var repo = this.createRepo(RepoType.PYPI, "pypi", null);
    final var user = this.panelToken(UserRole.USER);
    final var packages = "/api/pypi/packages/" + repo.getName();

    this.uploadPypiPackage(repo, "demo", "1.0.0");

    this.perform(delete(packages + "/demo").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(packages + "/demo/releases/1.0.0").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());

    for (final var authorization : this.callers(user)) {
      final var listed =
          this.getAs(packages, authorization)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(JsonPath.<List<String>>read(listed, "$.data.content[*].name"))
          .containsExactly("demo");
    }

    this.perform(get(packages + "/demo/releases").header(AUTHORIZATION, user))
        .andExpect(status().isOk());
  }

  // ---- Panel API: npm

  @Test
  @DisplayName("cannot delete an npm package or version, which stay, but can read them")
  void userCannotDeleteFromAPublicNpmRepoButCanReadIt() throws Exception {
    final var repo = this.createRepo(RepoType.NPM, "npm", null);
    final var user = this.panelToken(UserRole.USER);
    final var packages = "/api/npm/packages/" + repo.getName();
    final var scoped = "/api/npm/scopes/" + repo.getName() + "/tools/packages";

    this.seedNpmVersion(repo, null, "demo");
    this.seedNpmVersion(repo, "tools", "scoped");

    this.perform(delete(packages + "/demo").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(packages + "/demo/versions/1.0.0").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(scoped + "/scoped").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(scoped + "/scoped/versions/1.0.0").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());

    for (final var authorization : this.callers(user)) {
      final var listed =
          this.getAs(packages, authorization)
              .andExpect(status().isOk())
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(JsonPath.<List<String>>read(listed, "$.content[*].name")).contains("demo");
      this.getAs(packages + "/demo", authorization).andExpect(status().isOk());
      this.getAs(packages + "/demo/versions", authorization).andExpect(status().isOk());
      this.getAs(scoped + "/scoped", authorization).andExpect(status().isOk());
    }

    assertThat(this.npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), null, "demo"))
        .isPresent();
    assertThat(
            this.npmPackageRepository.findByRepoIdAndScopeAndName(repo.getId(), "tools", "scoped"))
        .isPresent();
  }

  private void seedNpmVersion(final Repo repo, final String scope, final String name) {
    final var pkg = new NpmPackage();
    pkg.setRepo(this.repoRepository.getReferenceById(repo.getId()));
    pkg.setScope(scope);
    pkg.setName(name);
    pkg.setLatest("1.0.0");
    final var saved = this.npmPackageRepository.save(pkg);
    final var version = new PackageVersion();
    version.setNpmPackage(saved);
    version.setVersion("1.0.0");
    this.npmPackageVersionRepository.save(version);
  }

  // ---- Panel API: Docker

  @Test
  @DisplayName("cannot delete a Docker image, which stays, but can list the images")
  void userCannotDeleteADockerImageButCanListThem() throws Exception {
    final var repo = this.createRepo(RepoType.DOCKER, "dkr", null);
    final var user = this.panelToken(UserRole.USER);
    final var images = "/api/docker/images/" + repo.getName();

    this.imageTxService.findOrCreateImage(repo.getId(), "app");

    this.perform(delete(images + "/app").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(delete(images + "/app/tags/latest").header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(
            delete("/api/docker/images/manifests/" + repo.getName() + "/untagged")
                .header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());
    this.perform(
            delete("/api/docker/images/blobs/" + repo.getName() + "/orphan-layers")
                .header(AUTHORIZATION, user))
        .andExpect(status().isForbidden());

    assertThat(this.imageRepository.findByRepoIdAndName(repo.getId(), "app")).isPresent();

    for (final var authorization : this.callers(user)) {
      this.getAs(images, authorization).andExpect(status().isOk());
    }
  }

  // ---- Panel API: deploy tokens and key stores

  @Test
  @DisplayName("cannot mint or list the deploy tokens of a public repo, and none is created")
  void userCannotManageDeployTokens() throws Exception {
    final var repo = this.createRepo(RepoType.MAVEN, "mvn", null);
    final var user = this.panelToken(UserRole.USER);
    final var tokens = "/api/repos/" + repo.getName() + "/deploy-tokens";

    // a token that writes, and the listing of the ones that exist
    this.perform(
            post(tokens)
                .header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"mine\",\"readOnly\":false}"))
        .andExpect(status().isForbidden());
    this.perform(get(tokens).header(AUTHORIZATION, user)).andExpect(status().isForbidden());

    final var listed =
        this.perform(get(tokens).header(AUTHORIZATION, this.panelToken(UserRole.ADMIN)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<Object>>read(listed, "$.content")).isEmpty();
  }

  @Test
  @DisplayName("cannot add a key store to a public Maven repo, and none is added")
  void userCannotAddAKeyStore() throws Exception {
    final var repo = this.createRepo(RepoType.MAVEN, "mvn", null);
    final var user = this.panelToken(UserRole.USER);
    final var keyStores = "/api/mvn/key-stores/" + repo.getName();

    this.perform(
            post(keyStores)
                .header(AUTHORIZATION, user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"allowedKeyserverId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden());
    this.perform(get(keyStores).header(AUTHORIZATION, user)).andExpect(status().isForbidden());

    final var listed =
        this.perform(get(keyStores).header(AUTHORIZATION, this.panelToken(UserRole.ADMIN)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<Object>>read(listed, "$.data.content")).isEmpty();
  }

  // ---- helpers

  private UserInfo newUser(final UserRole role) {
    final var user =
        this.userTxService.create(uniqueUsername("rps1602"), role, VALID_PASSWORD_HASH);

    this.createdUserIds.add(user.getId());

    return user;
  }

  private String panelToken(final UserRole role) {
    final var user = this.newUser(role);

    return this.bearerTokenFor(user.getId(), user.getUsername());
  }

  private String basicAuthOf(final UserInfo user) {
    return basicAuth(user.getUsername(), VALID_PASSWORD);
  }

  /**
   * Creates a public repo the way an admin does, through {@code POST /api/repos}. It is committed,
   * because the wire request is served in a transaction of its own and could not see the row of an
   * uncommitted test transaction.
   */
  private Repo createRepo(final RepoType type, final String prefix, final String description)
      throws Exception {

    final var name = uniqueRepoName(prefix);
    final var body =
        "{\"name\":\"%s\",\"type\":\"%s\",\"privateRepo\":false%s}"
            .formatted(
                name,
                type.name(),
                description == null ? "" : ",\"description\":\"" + description + "\"");

    this.perform(
            post("/api/repos")
                .header(AUTHORIZATION, this.panelToken(UserRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    final var repo = this.repoRepository.findByName(name).orElseThrow();

    this.createdRepoIds.add(repo.getId());

    return repo;
  }

  /** A GET of the panel API, with no {@code Authorization} header when there is no credential. */
  private ResultActions getAs(final String url, final String authorization) throws Exception {
    final var request = get(url);

    if (authorization != null) {
      request.header(AUTHORIZATION, authorization);
    }

    return this.perform(request);
  }

  /** The credentials of a signed-in user, then no credential at all. */
  private List<String> callers(final String user) {
    return Arrays.asList(user, null);
  }

  /** Deploys the demo pom and jar, each of which has to be answered with the given status. */
  private void deployDemo(final Repo repo, final String authorization, final int status)
      throws Exception {
    final var pom =
        put("/{repo}/{path}", repo.getName(), POM_PATH)
            .contentType(MediaType.APPLICATION_XML)
            .content(POM)
            .with(protocolPort());
    final var jar =
        put("/{repo}/{path}", repo.getName(), JAR_PATH)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(JAR)
            .with(protocolPort());

    if (authorization != null) {
      pom.header(AUTHORIZATION, authorization);
      jar.header(AUTHORIZATION, authorization);
    }

    for (final var file : List.of(pom, jar)) {
      final var response = this.mockMvc.perform(file).andReturn().getResponse();

      assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
    }
  }

  private List<String> listedArtifactNames(final Repo repo, final String authorization)
      throws Exception {

    final var body =
        this.getAs("/api/mvn/artifacts/" + repo.getName(), authorization)
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    return JsonPath.read(body, "$.data.content[*].artifactName");
  }

  private void uploadPypiPackage(final Repo repo, final String name, final String version)
      throws Exception {

    final var repoInfo = this.repoTxService.getRepoByNameAndType(repo.getName(), RepoType.PYPI);
    final var filename = name + "-" + version + ".tar.gz";
    final var content = sdist();
    final var digest =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    final var parameters = new HashMap<String, Object>();

    parameters.put("name", name);
    parameters.put("version", version);
    parameters.put("filetype", "sdist");
    parameters.put("pyversion", "source");
    parameters.put("metadata_version", "2.1");
    parameters.put("summary", "RPS-1602 fixture");
    parameters.put("sha256_digest", digest);

    final var context = new ProtocolContext();

    context.addProperty(
        "urlProperties",
        UrlParserProperties.builder()
            .repoName(repo.getName())
            .relativePath(new RelativePath(""))
            .repoInfo(repoInfo.orElseThrow())
            .build());
    this.pypiProtocolFacade.uploadPackage(
        context,
        parameters,
        new MockMultipartFile(
            "content", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE, content));
  }

  private static byte[] sdist() throws Exception {
    final var output = new ByteArrayOutputStream();
    final var bytes = "demo".getBytes(StandardCharsets.UTF_8);

    try (var gzip = new GZIPOutputStream(output);
        var tar = new TarArchiveOutputStream(gzip)) {
      final var entry = new TarArchiveEntry("fixture/data.txt");

      entry.setSize(bytes.length);
      tar.putArchiveEntry(entry);
      tar.write(bytes);
      tar.closeArchiveEntry();
      tar.finish();
    }

    return output.toByteArray();
  }
}
