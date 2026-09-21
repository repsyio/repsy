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
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.storage.services.MavenStorageService;
import io.repsy.os.shared.auth.utils.PasswordHasher;
import io.repsy.os.shared.repo.entities.Repo;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * RPS-1186: the signature of a POM ({@code .pom.asc}) is verified before it is stored, so a refused
 * one changes nothing. RPS-1191: that holds for a body that is not a signature at all (invalid
 * armor answers 422, it was a 500) and for a signature that verifies but whose POM has no
 * registered version (404 {@code artifactVersionNotFound}, it used to be stored first). Such a POM
 * can no longer be uploaded since RPS-1193 (a POM of another group is refused), so the test removes
 * the rows of a stored one instead.
 *
 * <p>It used to be stored first and verified second, and a refusal was rolled back by deleting the
 * version the signature belongs to: the artifact too when that was its only version, the group too
 * when that was its only artifact, together with every file accepted before and a good version that
 * an override had just replaced, while the usage counter kept counting the deleted bytes. For a
 * timestamped snapshot with other versions in the repo the rollback even looked up the wrong name
 * and ended in a 500.
 *
 * <p>The key server is an in-memory {@link WebClient} that answers every lookup with the public key
 * of {@link #KEYS}, so the signatures are checked for real and nothing leaves the machine.
 *
 * <p>Runs without a test transaction, like {@link MavenPomStorageConsistencyIT}: an accepted POM
 * inserts its artifact row in its own transaction, which cannot see an uncommitted repo row. It
 * deletes the repos and users it commits. {@link UsageUpdateService} is mocked to see what an
 * upload reported.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven POM signature is verified before it is stored (RPS-1186, RPS-1191)")
class MavenPomSignatureIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();
  private static final PgpTestKeys OTHER_KEYS = PgpTestKeys.generate();

  private static final String GROUP_DIR = "com/acme/lib";
  private static final String RELEASE_POM = "com/acme/lib/1.0/lib-1.0.pom";
  private static final String RELEASE_JAR = "com/acme/lib/1.0/lib-1.0.jar";
  private static final String RELEASE_ASC = RELEASE_POM + ".asc";
  private static final String NEXT_POM = "com/acme/lib/2.0/lib-2.0.pom";
  private static final String NEXT_JAR = "com/acme/lib/2.0/lib-2.0.jar";
  private static final String NEXT_ASC = NEXT_POM + ".asc";
  private static final String SNAPSHOT_POM =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.pom";
  private static final String SNAPSHOT_JAR =
      "com/acme/lib/1.0-SNAPSHOT/lib-1.0-20260921.101010-1.jar";
  private static final String SNAPSHOT_ASC = SNAPSHOT_POM + ".asc";
  private static final String ARTIFACT_METADATA = "com/acme/lib/maven-metadata.xml";
  private static final String METADATA =
      """
      <metadata><groupId>com.acme</groupId><artifactId>lib</artifactId><versioning><versions>\
      <version>1.0</version><version>2.0</version></versions>\
      <lastUpdated>20260921101010</lastUpdated></versioning></metadata>""";

  private static final String NOT_VERIFIED = "artifactSignatureNotVerified";

  /**
   * Replaces the key-server client of {@code PGPVerifierWebClientConfig}, see {@link #keyServer}.
   */
  @TestBean(name = "pgpVerifierWebClient", methodName = "keyServer")
  private WebClient keyServer;

  @MockitoBean private UsageUpdateService usageUpdateService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** A key server that has the public key of {@link #KEYS}, and no other, on every host. */
  @SuppressWarnings("unused")
  static WebClient keyServer() {
    return WebClient.builder()
        .exchangeFunction(
            request ->
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "text/plain")
                        .body(KEYS.armoredPublicKey())
                        .build()))
        .build();
  }

  @AfterEach
  void deleteCommittedData() {
    // Every table that references a repo cascades on delete, so this takes the artifacts with it.
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
  }

  private Repo mavenRepo(final boolean allowOverride) {
    final var name = uniqueRepoName("mvn-sig");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setAllowOverride(allowOverride);
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

  private static byte[] pom(final String version) {
    return pomOfGroup("com.acme", version);
  }

  private static byte[] pomOfGroup(final String groupId, final String version) {
    return """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>%s</groupId>
          <artifactId>lib</artifactId>
          <version>%s</version>
        </project>
        """
        .formatted(groupId, version)
        .getBytes(UTF_8);
  }

  private static byte[] jar(final String version) {
    return ("jar " + version).getBytes(UTF_8);
  }

  private static byte[] goodSignatureOf(final byte[] pom) {
    return KEYS.detachedSignature(pom).getBytes(UTF_8);
  }

  /** A well-formed signature of the right key that was made over another file. */
  private static byte[] badSignatureOf(final byte[] pom) {
    return KEYS.detachedSignature(("not " + new String(pom, UTF_8)).getBytes(UTF_8))
        .getBytes(UTF_8);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private static void expectSignatureRefused(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.UNPROCESSABLE_ENTITY,
        NOT_VERIFIED,
        NOT_VERIFIED,
        "Artifact signature isn't verified!");
  }

  /** {@code signed} of one version row, or an empty list when the row does not exist. */
  private List<Boolean> signedOf(final Repo repo, final String artifact, final String version) {
    return this.jdbcTemplate.queryForList(
        """
        select v.signed from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
         where a.repo_id = ? and a.group_name = 'com.acme' and a.artifact_name = ?
           and v.version_name = ?""",
        Boolean.class,
        repo.getId(),
        artifact,
        version);
  }

  private int artifactCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_artifact where repo_id = ?", Integer.class, repo.getId());
  }

  /** What the uploads so far reported to the usage counter, in order. */
  private List<UsageChangedInfo> reportedUsage() {
    return mockingDetails(this.usageUpdateService).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("updateUsage"))
        .map(invocation -> invocation.<UsageChangedInfo>getArgument(0))
        .toList();
  }

  private static UsageChangedInfo usageOf(final Repo repo, final byte[] file) {
    return new UsageChangedInfo(repo.getId(), BaseUsages.ofDisk(file.length));
  }

  @Test
  @DisplayName("a refused signature of a new release leaves the POM, the jar and the version")
  void aRefusedSignatureOfANewReleaseKeepsThePomAndJar() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0");
    final var jar = jar("1.0");
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    this.uploadOk(repo, admin, RELEASE_JAR, jar);

    expectSignatureRefused(this.upload(repo, admin, RELEASE_ASC, badSignatureOf(pom)));

    assertThat(Files.readAllBytes(stored(repo, RELEASE_POM))).isEqualTo(pom);
    assertThat(Files.readAllBytes(stored(repo, RELEASE_JAR))).isEqualTo(jar);
    assertThat(stored(repo, RELEASE_ASC)).doesNotExist();
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(false);
    assertThat(this.reportedUsage()).containsExactly(usageOf(repo, pom), usageOf(repo, jar));
  }

  @Test
  @DisplayName("a refused signature never removes a good signature that is already stored")
  void aRefusedSignatureNeverRemovesAnExistingGoodSignature() throws Exception {
    final var repo = this.mavenRepo(true);
    final var admin = this.admin();
    final var pom = pom("1.0");
    final var good = goodSignatureOf(pom);
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    this.uploadOk(repo, admin, RELEASE_JAR, jar("1.0"));
    this.uploadOk(repo, admin, RELEASE_ASC, good);
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(true);

    expectSignatureRefused(this.upload(repo, admin, RELEASE_ASC, badSignatureOf(pom)));

    assertThat(Files.readAllBytes(stored(repo, RELEASE_ASC))).isEqualTo(good);
    assertThat(Files.readAllBytes(stored(repo, RELEASE_POM))).isEqualTo(pom);
    assertThat(Files.readAllBytes(stored(repo, RELEASE_JAR))).isEqualTo(jar("1.0"));
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(true);
  }

  @Test
  @DisplayName("a refused signature of a timestamped snapshot answers 422 and keeps the version")
  void aRefusedTimestampedSnapshotSignatureIsA422AndKeepsTheVersion() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0-SNAPSHOT");
    this.uploadOk(repo, admin, SNAPSHOT_POM, pom);
    this.uploadOk(repo, admin, SNAPSHOT_JAR, jar("snapshot"));

    expectSignatureRefused(this.upload(repo, admin, SNAPSHOT_ASC, badSignatureOf(pom)));

    assertThat(Files.readAllBytes(stored(repo, SNAPSHOT_POM))).isEqualTo(pom);
    assertThat(stored(repo, SNAPSHOT_JAR)).exists();
    assertThat(stored(repo, SNAPSHOT_ASC)).doesNotExist();
    assertThat(this.signedOf(repo, "lib", "1.0-SNAPSHOT")).containsExactly(false);
  }

  @Test
  @DisplayName("the same, when the artifact has other versions (it used to end in a 500)")
  void aRefusedTimestampedSnapshotSignatureIsA422WhenTheArtifactHasOtherVersions()
      throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0-SNAPSHOT");
    this.uploadOk(repo, admin, NEXT_POM, pom("2.0"));
    this.uploadOk(repo, admin, SNAPSHOT_POM, pom);
    this.uploadOk(repo, admin, SNAPSHOT_JAR, jar("snapshot"));

    expectSignatureRefused(this.upload(repo, admin, SNAPSHOT_ASC, badSignatureOf(pom)));

    assertThat(Files.readAllBytes(stored(repo, SNAPSHOT_POM))).isEqualTo(pom);
    assertThat(stored(repo, SNAPSHOT_JAR)).exists();
    assertThat(stored(repo, SNAPSHOT_ASC)).doesNotExist();
    assertThat(this.signedOf(repo, "lib", "1.0-SNAPSHOT")).containsExactly(false);
    assertThat(this.signedOf(repo, "lib", "2.0")).containsExactly(false);
  }

  @Test
  @DisplayName("a refused signature of one version keeps the other versions and the metadata")
  void aRefusedSignatureOfOneVersionKeepsTheOthers() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var nextPom = pom("2.0");
    this.uploadOk(repo, admin, RELEASE_POM, pom("1.0"));
    this.uploadOk(repo, admin, RELEASE_JAR, jar("1.0"));
    this.uploadOk(repo, admin, NEXT_POM, nextPom);
    this.uploadOk(repo, admin, NEXT_JAR, jar("2.0"));
    this.uploadOk(repo, admin, ARTIFACT_METADATA, METADATA.getBytes(UTF_8));

    expectSignatureRefused(this.upload(repo, admin, NEXT_ASC, badSignatureOf(nextPom)));

    assertThat(Files.readAllBytes(stored(repo, NEXT_POM))).isEqualTo(nextPom);
    assertThat(stored(repo, NEXT_JAR)).exists();
    assertThat(stored(repo, RELEASE_POM)).exists();
    assertThat(Files.readString(stored(repo, ARTIFACT_METADATA))).isEqualTo(METADATA);
    assertThat(this.signedOf(repo, "lib", "2.0")).containsExactly(false);
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(false);
    assertThat(this.artifactCount(repo)).isEqualTo(1);
    assertThat(stored(repo, GROUP_DIR)).isDirectory();
  }

  @Test
  @DisplayName("a valid signature is stored, marks the version signed and is counted")
  void aValidSignatureMarksTheVersionSigned() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0");
    final var signature = goodSignatureOf(pom);
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(false);

    this.uploadOk(repo, admin, RELEASE_ASC, signature);

    assertThat(Files.readAllBytes(stored(repo, RELEASE_ASC))).isEqualTo(signature);
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(true);
    assertThat(this.reportedUsage()).containsExactly(usageOf(repo, pom), usageOf(repo, signature));
  }

  @Test
  @DisplayName("a signature of a key no server knows answers 404 and stores nothing")
  void aSignatureOfAnUnknownKeyIs404AndStoresNothing() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0");
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    final var unknown = OTHER_KEYS.detachedSignature(pom).getBytes(UTF_8);

    assertThat(this.upload(repo, admin, RELEASE_ASC, unknown).andReturn().getResponse().getStatus())
        .isEqualTo(404);

    assertThat(stored(repo, RELEASE_ASC)).doesNotExist();
    assertThat(Files.readAllBytes(stored(repo, RELEASE_POM))).isEqualTo(pom);
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(false);
  }

  @Test
  @DisplayName("a signature that arrives before its POM answers 404 and stores nothing")
  void aSignatureBeforeItsPomIs404AndStoresNothing() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();

    expectError(
        this.upload(repo, admin, RELEASE_ASC, goodSignatureOf(pom("1.0"))),
        HttpStatus.NOT_FOUND,
        "itemNotFound",
        "itemNotFound",
        "The requested item is not found.");

    assertThat(stored(repo, RELEASE_ASC)).doesNotExist();
    assertThat(stored(repo, GROUP_DIR)).doesNotExist();
    assertThat(this.artifactCount(repo)).isZero();
    assertThat(this.reportedUsage()).isEmpty();
  }

  @Test
  @DisplayName(
      "an .asc with invalid armor answers 422 (it was a 500) and stores nothing (RPS-1191)")
  void anAscWithInvalidArmorIs422AndStoresNothing() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0");
    final var jar = jar("1.0");
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    this.uploadOk(repo, admin, RELEASE_JAR, jar);
    final var armorOnly =
        "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n".getBytes(UTF_8);

    expectSignatureRefused(this.upload(repo, admin, RELEASE_ASC, armorOnly));

    assertThat(Files.readAllBytes(stored(repo, RELEASE_POM))).isEqualTo(pom);
    assertThat(Files.readAllBytes(stored(repo, RELEASE_JAR))).isEqualTo(jar);
    assertThat(stored(repo, RELEASE_ASC)).doesNotExist();
    assertThat(this.signedOf(repo, "lib", "1.0")).containsExactly(false);
    assertThat(this.reportedUsage()).containsExactly(usageOf(repo, pom), usageOf(repo, jar));
  }

  @Test
  @DisplayName(
      "a verified signature of a POM whose version row is gone answers 404, stores nothing")
  void aVerifiedSignatureOfAPomWhoseVersionRowIsGoneIs404AndStoresNothing() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pom("1.0");
    this.uploadOk(repo, admin, RELEASE_POM, pom);
    // A POM stored before RPS-1193 has no rows when its groupId is not its directory's. It can no
    // longer be uploaded, so the rows are removed the way the panel would (the versions cascade).
    this.jdbcTemplate.update(
        "delete from maven_artifact where repo_id = ? and group_name = 'com.acme'"
            + " and artifact_name = 'lib'",
        repo.getId());
    assertThat(this.artifactCount(repo)).isZero();
    assertThat(this.signedOf(repo, "lib", "1.0")).isEmpty();

    expectError(
        this.upload(repo, admin, RELEASE_ASC, goodSignatureOf(pom)),
        HttpStatus.NOT_FOUND,
        "artifactVersionNotFound",
        "artifactVersionNotFound",
        "Artifact version is not found.");

    assertThat(stored(repo, RELEASE_ASC)).doesNotExist();
    assertThat(Files.readAllBytes(stored(repo, RELEASE_POM))).isEqualTo(pom);
    assertThat(this.artifactCount(repo)).isZero();
    assertThat(this.reportedUsage()).containsExactly(usageOf(repo, pom));
  }

  @Test
  @DisplayName("a POM of another group is refused before it is stored, so it has no signature")
  void aPomOfAnotherGroupIsRefusedBeforeItIsStored() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    final var pom = pomOfGroup("org.other", "1.0");

    expectError(
        this.upload(repo, admin, RELEASE_POM, pom),
        HttpStatus.BAD_REQUEST,
        "pomGroupIdMismatch",
        "pomGroupIdMismatch",
        "The POM declares a groupId that is not the one of its path; its <groupId> (or"
            + " <parent><groupId>) must equal the directory group.");

    assertThat(stored(repo, RELEASE_POM)).doesNotExist();
    assertThat(this.artifactCount(repo)).isZero();
    assertThat(this.reportedUsage()).isEmpty();
  }
}
