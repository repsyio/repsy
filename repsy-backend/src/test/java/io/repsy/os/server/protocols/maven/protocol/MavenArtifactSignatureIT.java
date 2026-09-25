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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1188: a Maven repo with {@code pgpVerifyAllSignaturesEnabled} verifies every artifact {@code
 * .asc} against the file it signs before it stores it (like the {@code .pom.asc}), and a version is
 * {@code signed} only when every signable file of it (for a snapshot: of its newest build) has a
 * verified signature. A repo without the setting keeps today's behaviour: only the {@code .pom.asc}
 * is verified and the other signatures are stored as sent.
 *
 * <p>The key server is an in-memory {@link WebClient} that serves the public key of every key in
 * {@link #SERVED_KEYS}, so the signatures are checked for real and nothing leaves the machine. Most
 * tests register the key on the repo instead and need no server at all.
 *
 * <p>Runs without a test transaction, like {@link MavenPomSignatureIT}, and deletes the repos and
 * users it commits (the signature rows cascade with the repo).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven verifies every artifact signature when the repo asks for it (RPS-1188)")
class MavenArtifactSignatureIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();
  private static final Pattern SEARCH = Pattern.compile("search=0x([0-9A-Fa-f]{16})");

  /** The keys the fake key server has, by upper-case hex key id. */
  private static final Map<String, PgpTestKeys> SERVED_KEYS = new ConcurrentHashMap<>();

  private static final AtomicInteger KEY_SERVER_REQUESTS = new AtomicInteger();

  private static final String DIR = "com/acme/lib/1.0/";
  private static final String POM = DIR + "lib-1.0.pom";
  private static final String JAR = DIR + "lib-1.0.jar";
  private static final String SOURCES = DIR + "lib-1.0-sources.jar";
  private static final String MODULE = DIR + "lib-1.0.module";
  private static final String SNAPSHOT_DIR = "com/acme/lib/1.0-SNAPSHOT/";
  private static final String BUILD_1 = SNAPSHOT_DIR + "lib-1.0-20260921.101010-1";
  private static final String BUILD_2 = SNAPSHOT_DIR + "lib-1.0-20260921.101010-2";
  private static final String NOT_VERIFIED = "artifactSignatureNotVerified";
  private static final String METADATA =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning><versions>\
      <version>1.0</version></versions><lastUpdated>20260921101010</lastUpdated></versioning>\
      </metadata>""";

  @TestBean(name = "pgpVerifierWebClient", methodName = "keyServer")
  private WebClient keyServer;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** A key server that has the public key of every key in {@link #SERVED_KEYS}. */
  @SuppressWarnings("unused")
  static WebClient keyServer() {
    return WebClient.builder()
        .exchangeFunction(
            request -> {
              KEY_SERVER_REQUESTS.incrementAndGet();

              final var matcher = SEARCH.matcher(request.url().toString());
              final var keys =
                  matcher.find() ? SERVED_KEYS.get(matcher.group(1).toUpperCase()) : null;

              if (keys == null) {
                return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
              }

              return Mono.just(
                  ClientResponse.create(HttpStatus.OK)
                      .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                      .body(keys.armoredPublicKey())
                      .build());
            })
        .build();
  }

  @BeforeEach
  void resetKeyServer() {
    KEY_SERVER_REQUESTS.set(0);
    SERVED_KEYS.clear();
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the artifacts and the
    // signature rows with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo(final boolean verifyAll) {
    final var name = uniqueRepoName("mvn-allsig");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(verifyAll);
    this.repoRepository.saveAndFlush(managed);

    return this.repoRepository.findByName(name).orElseThrow();
  }

  private User admin() {
    final var userInfo =
        this.userTxService.create(
            uniqueUsername("mvn-admin"), UserRole.ADMIN, PasswordHasher.hash(VALID_PASSWORD));
    this.createdUserIds.add(userInfo.getId());

    return this.userRepository.findById(userInfo.getId()).orElseThrow();
  }

  private ResultActions upload(
      final Repo repo, final User admin, final String path, final byte[] body) throws Exception {
    return this.mockMvc.perform(
        put("/{repo}/{path}", repo.getName(), path)
            .header(AUTHORIZATION, this.protocolBearerTokenFor(admin))
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .content(body)
            .with(protocolPort()));
  }

  private void uploadOk(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    assertThat(this.upload(repo, admin, path, body).andReturn().getResponse().getStatus())
        .as("PUT %s", path)
        .isEqualTo(200);
  }

  private void registerPublicKey(final Repo repo, final User admin, final PgpTestKeys keys)
      throws Exception {
    final var body =
        this.objectMapper.writeValueAsString(Map.of("armoredKey", keys.armoredPublicKey()));

    final var result =
        this.mockMvc
            .perform(
                post("/api/mvn/key-stores/" + repo.getName() + "/public-keys")
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(admin))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();

    assertThat(result.getResponse().getStatus()).as("register public key").isEqualTo(200);
  }

  /** A repo verifying every signature, an admin and {@link #KEYS} registered on the repo. */
  private Fixture fixture(final boolean verifyAll) throws Exception {
    final var repo = this.mavenRepo(verifyAll);
    final var admin = this.admin();
    this.registerPublicKey(repo, admin, KEYS);

    return new Fixture(repo, admin);
  }

  private record Fixture(Repo repo, User admin) {}

  private static byte[] pom(final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(version)
        .getBytes(UTF_8);
  }

  private static byte[] pom(final String version, final String description) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.acme</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
          <description>%s</description>
        </project>
        """
        .formatted(version, description)
        .getBytes(UTF_8);
  }

  private static byte[] bytes(final String text) {
    return text.getBytes(UTF_8);
  }

  private static byte[] sign(final byte[] file) {
    return KEYS.detachedSignature(file).getBytes(UTF_8);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private void expectSignatureRefused(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNPROCESSABLE_ENTITY,
        NOT_VERIFIED,
        NOT_VERIFIED,
        "Artifact signature isn't verified!");
  }

  private List<Boolean> signedOf(final Repo repo, final String version) {
    return this.jdbcTemplate.queryForList(
        """
        select v.signed from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? and a.group_name = 'com.acme' and a.artifact_name = 'lib'
            and v.version_name = ?""",
        Boolean.class,
        repo.getId(),
        version);
  }

  /** The names of the files of a version whose signature was verified, sorted. */
  private List<String> verifiedFiles(final Repo repo, final String version) {
    return this.jdbcTemplate.queryForList(
        """
        select s.file_name from maven_version_signature s
          join maven_artifact_version v on v.id = s.artifact_version_id
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ? and v.version_name = ?
          order by s.file_name""",
        String.class,
        repo.getId(),
        version);
  }

  private int signatureRowCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        """
        select count(*) from maven_version_signature s
          join maven_artifact_version v on v.id = s.artifact_version_id
          join maven_artifact a on a.id = v.artifact_id
          where a.repo_id = ?""",
        Integer.class,
        repo.getId());
  }

  private int pendingCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_pending_signature where repo_id = ?",
        Integer.class,
        repo.getId());
  }

  private void allowOverride(final Repo repo) {
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setAllowOverride(true);
    this.repoRepository.saveAndFlush(managed);
  }

  @Test
  @DisplayName("by default a jar signature is stored as sent, unverified, and marks nothing")
  void byDefaultAJarSignatureIsStoredUnverified() throws Exception {
    final var f = this.fixture(false);
    final var pom = pom("1.0");
    final var garbage = bytes("this is not a signature");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, bytes("jar"));

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", garbage);
    this.uploadOk(f.repo(), f.admin(), SOURCES + ".asc", garbage);

    assertThat(Files.readAllBytes(stored(f.repo(), JAR + ".asc"))).isEqualTo(garbage);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).isEmpty();
    assertThat(KEY_SERVER_REQUESTS.get()).isZero();

    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));

    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.pom");
  }

  @Test
  @DisplayName("a version is signed once every file has a verified signature, in any order")
  void aVersionIsSignedWhenEveryFileHasAVerifiedSignature() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);

    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.jar", "lib-1.0.pom");
  }

  @Test
  @DisplayName("the POM signature may come first: the version is signed at the last signature")
  void thePomSignatureMayComeFirst() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);

    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a new signable file un-signs the version until its own signature is verified")
  void aNewFileUnsignsTheVersionUntilItIsSigned() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    final var sources = bytes("sources");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);

    this.uploadOk(f.repo(), f.admin(), SOURCES, sources);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);

    this.uploadOk(f.repo(), f.admin(), SOURCES + ".asc", sign(sources));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);

    final var module = bytes("{\"formatVersion\":\"1.1\"}");
    this.uploadOk(f.repo(), f.admin(), MODULE, module);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);

    this.uploadOk(f.repo(), f.admin(), MODULE + ".asc", sign(module));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
    assertThat(this.verifiedFiles(f.repo(), "1.0"))
        .containsExactlyInAnyOrder(
            "lib-1.0-sources.jar", "lib-1.0.jar", "lib-1.0.module", "lib-1.0.pom");
  }

  @Test
  @DisplayName("a file stored again loses its verified signature, and so does the POM")
  void aFileStoredAgainLosesItsSignature() throws Exception {
    final var f = this.fixture(true);
    this.allowOverride(f.repo());
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);

    final var newJar = bytes("another jar");
    this.uploadOk(f.repo(), f.admin(), JAR, newJar);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.pom");

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(newJar));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);

    final var newPom = pom("1.0", "another description");
    this.uploadOk(f.repo(), f.admin(), POM, newPom);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.jar");

    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(newPom));
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a file stored again with the same bytes keeps its signature: it still verifies")
  void aFileStoredAgainWithTheSameBytesKeepsItsSignature() throws Exception {
    final var f = this.fixture(true);
    this.allowOverride(f.repo());
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));

    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM, pom);

    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
    assertThat(this.verifiedFiles(f.repo(), "1.0"))
        .containsExactlyInAnyOrder("lib-1.0.jar", "lib-1.0.pom");
  }

  @Test
  @DisplayName("a jar signature made over other bytes answers 422 and changes nothing")
  void aBadJarSignatureIsRefusedAndStoresNothing() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, bytes("jar"));
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));

    this.expectSignatureRefused(
        this.upload(f.repo(), f.admin(), JAR + ".asc", sign(bytes("some other jar"))));
    this.expectSignatureRefused(
        this.upload(
            f.repo(),
            f.admin(),
            JAR + ".asc",
            bytes("-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n")));

    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.pom");
  }

  @Test
  @DisplayName("a refused re-upload of a signature keeps the good one and the version signed")
  void aRefusedSignatureKeepsTheGoodOne() throws Exception {
    final var f = this.fixture(true);
    this.allowOverride(f.repo());
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    final var good = sign(jar);
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", good);

    this.expectSignatureRefused(
        this.upload(f.repo(), f.admin(), JAR + ".asc", sign(bytes("some other jar"))));

    assertThat(Files.readAllBytes(stored(f.repo(), JAR + ".asc"))).isEqualTo(good);
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a signature before the file it signs is parked, and verified when the file arrives")
  void aSignatureBeforeItsFileIsParkedAndVerifiedWhenTheFileArrives() throws Exception {
    final var f = this.fixture(true);
    final var jar = bytes("jar");
    final var signature = sign(jar);
    this.uploadOk(f.repo(), f.admin(), POM, pom("1.0"));

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", signature);

    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);
    assertThat(this.signatureRowCount(f.repo())).isZero();

    this.uploadOk(f.repo(), f.admin(), JAR, jar);

    assertThat(Files.readAllBytes(stored(f.repo(), JAR + ".asc"))).isEqualTo(signature);
    assertThat(this.pendingCount(f.repo())).isZero();
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.jar");
  }

  @Test
  @DisplayName("a jar signature before the POM is parked, and verified when the POM registers")
  void aJarSignatureBeforeThePomIsParkedAndVerifiedWhenThePomRegisters() throws Exception {
    final var f = this.fixture(true);
    final var jar = bytes("jar");
    final var pom = pom("1.0");
    this.uploadOk(f.repo(), f.admin(), JAR, jar);

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));

    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);

    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));

    assertThat(stored(f.repo(), JAR + ".asc")).exists();
    assertThat(this.pendingCount(f.repo())).isZero();
    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a checksum of a signature and a metadata signature are stored unverified")
  void checksumsAndMetadataSignaturesAreNeverVerified() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), JAR, jar);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));

    this.uploadOk(
        f.repo(), f.admin(), JAR + ".asc.sha1", bytes("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    this.uploadOk(
        f.repo(), f.admin(), "com/acme/lib/maven-metadata.xml.asc", bytes("not verified"));

    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(false);
    assertThat(this.verifiedFiles(f.repo(), "1.0")).containsExactly("lib-1.0.pom");

    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign(jar));
    this.uploadOk(
        f.repo(), f.admin(), JAR + ".sha1", bytes("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
    this.uploadOk(f.repo(), f.admin(), "com/acme/lib/maven-metadata.xml", bytes(METADATA));

    assertThat(this.signedOf(f.repo(), "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a snapshot is signed when its newest build is, older builds do not count")
  void aSnapshotIsSignedByItsNewestBuild() throws Exception {
    final var f = this.fixture(true);
    this.allowOverride(f.repo());
    final var pom1 = pom("1.0-SNAPSHOT");
    final var jar1 = bytes("jar build 1");
    this.uploadOk(f.repo(), f.admin(), BUILD_1 + ".pom", pom1);
    this.uploadOk(f.repo(), f.admin(), BUILD_1 + ".jar", jar1);
    this.uploadOk(f.repo(), f.admin(), BUILD_1 + ".pom.asc", sign(pom1));
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(false);
    this.uploadOk(f.repo(), f.admin(), BUILD_1 + ".jar.asc", sign(jar1));
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(true);

    final var pom2 = pom("1.0-SNAPSHOT");
    final var jar2 = bytes("jar build 2");
    this.uploadOk(f.repo(), f.admin(), BUILD_2 + ".pom", pom2);
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(false);
    this.uploadOk(f.repo(), f.admin(), BUILD_2 + ".jar", jar2);
    this.uploadOk(f.repo(), f.admin(), BUILD_2 + ".pom.asc", sign(pom2));
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(false);
    this.uploadOk(f.repo(), f.admin(), BUILD_2 + ".jar.asc", sign(jar2));
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(true);

    // Build 1 was superseded: storing one of its files again does not un-sign the version.
    this.uploadOk(f.repo(), f.admin(), BUILD_1 + ".jar", bytes("jar build 1 again"));
    assertThat(this.signedOf(f.repo(), "1.0-SNAPSHOT")).containsExactly(true);
  }

  @Test
  @DisplayName("the signature rows go with their version")
  void theSignatureRowsGoWithTheirVersion() throws Exception {
    final var f = this.fixture(true);
    final var pom = pom("1.0");
    this.uploadOk(f.repo(), f.admin(), POM, pom);
    this.uploadOk(f.repo(), f.admin(), POM + ".asc", sign(pom));
    assertThat(this.signatureRowCount(f.repo())).isEqualTo(1);

    this.jdbcTemplate.update(
        "delete from maven_artifact_version where artifact_id in"
            + " (select id from maven_artifact where repo_id = ?)",
        f.repo().getId());

    assertThat(this.signatureRowCount(f.repo())).isZero();
  }

  @Test
  @DisplayName("every signature of a deploy by one unregistered key asks the key server once")
  void oneKeyServerCallServesTheWholeDeploy() throws Exception {
    final var repo = this.mavenRepo(true);
    final var admin = this.admin();
    final var unregistered = PgpTestKeys.generate();
    SERVED_KEYS.put("%016X".formatted(unregistered.keyId()), unregistered);
    final var pom = pom("1.0");
    final var jar = bytes("jar");
    final var sources = bytes("sources");
    this.uploadOk(repo, admin, POM, pom);
    this.uploadOk(repo, admin, JAR, jar);
    this.uploadOk(repo, admin, SOURCES, sources);

    this.uploadOk(repo, admin, POM + ".asc", unregistered.detachedSignature(pom).getBytes(UTF_8));
    this.uploadOk(repo, admin, JAR + ".asc", unregistered.detachedSignature(jar).getBytes(UTF_8));
    this.uploadOk(
        repo, admin, SOURCES + ".asc", unregistered.detachedSignature(sources).getBytes(UTF_8));

    assertThat(this.signedOf(repo, "1.0")).containsExactly(true);
    assertThat(KEY_SERVER_REQUESTS.get()).isEqualTo(1);
  }
}
