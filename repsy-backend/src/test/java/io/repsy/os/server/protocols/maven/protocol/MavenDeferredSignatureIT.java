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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.repsy.os.AbstractIntegrationTest;
import io.repsy.os.server.protocols.maven.shared.artifact.services.PendingSignatureService;
import io.repsy.os.server.protocols.maven.shared.keystore.PgpTestKeys;
import io.repsy.os.server.protocols.maven.shared.keystore.services.PGPVerifierService;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * RPS-1188: on a repo that verifies every signature a signature may arrive before the file it
 * signs, or before the POM that registers the version, as it does in a real parallel {@code mvn
 * deploy}. It is parked (not stored, not served, not charged) and verified when the file arrives.
 * Whatever the order of the six requests of a deploy (a POM, a jar, a sources jar and their
 * signatures) the outcome is the same: every request answers 200, the three signatures are stored
 * byte-equal, three are recorded, none is left parked and the version is signed.
 *
 * <p>The signer's key is registered on the repo, so nothing asks a key server ({@code
 * pgpVerifierWebClient} answers 404 to anything and counts). Runs without a test transaction, like
 * {@link MavenArtifactSignatureIT}.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Maven signatures may arrive before their file (RPS-1188)")
class MavenDeferredSignatureIT extends AbstractIntegrationTest {

  private static final PgpTestKeys KEYS = PgpTestKeys.generate();
  private static final AtomicInteger KEY_SERVER_REQUESTS = new AtomicInteger();

  private static final String DIR = "com/acme/lib/1.0/";
  private static final String POM = DIR + "lib-1.0.pom";
  private static final String JAR = DIR + "lib-1.0.jar";
  private static final String SOURCES = DIR + "lib-1.0-sources.jar";
  private static final List<String> SIX =
      List.of(POM, JAR, SOURCES, POM + ".asc", JAR + ".asc", SOURCES + ".asc");

  @TestBean(name = "pgpVerifierWebClient", methodName = "keyServer")
  private WebClient keyServer;

  @MockitoBean private UsageUpdateService usageUpdateService;
  @MockitoSpyBean private PGPVerifierService pgpVerifierService;

  @Autowired private RepoTxService repoTxService;
  @Autowired private MavenStorageService mavenStorageService;
  @Autowired private PendingSignatureService pendingSignatureService;
  @Autowired private ObjectMapper objectMapper;

  private final List<UUID> createdRepoIds = new ArrayList<>();
  private final List<UUID> createdUserIds = new ArrayList<>();

  /** A key server that has no key at all, and counts what it is asked. */
  @SuppressWarnings("unused")
  static WebClient keyServer() {
    return WebClient.builder()
        .exchangeFunction(
            request -> {
              KEY_SERVER_REQUESTS.incrementAndGet();

              return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build());
            })
        .build();
  }

  @BeforeEach
  void reset() {
    KEY_SERVER_REQUESTS.set(0);
  }

  @AfterEach
  void deleteCommittedData() {
    this.createdRepoIds.forEach(
        id -> this.jdbcTemplate.update("delete from repo where id = ?", id));
    this.createdRepoIds.clear();
    this.deleteCommittedUsers(this.createdUserIds);
    this.createdUserIds.clear();
    org.mockito.Mockito.reset(this.pgpVerifierService);
  }

  private Repo mavenRepo(final boolean verifyAll) {
    final var name = uniqueRepoName("mvn-defer");
    final var created = this.repoTxService.createRepo(name, RepoType.MAVEN, false, null);
    this.createdRepoIds.add(created.getId());
    this.mavenStorageService.createRepo(created.getId());

    final var managed = this.repoRepository.findByName(name).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(verifyAll);
    managed.setAllowOverride(true);
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

  private int status(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    return this.upload(repo, admin, path, body).andReturn().getResponse().getStatus();
  }

  private void uploadOk(final Repo repo, final User admin, final String path, final byte[] body)
      throws Exception {
    assertThat(this.status(repo, admin, path, body)).as("PUT %s", path).isEqualTo(200);
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

  private record Fixture(Repo repo, User admin, Map<String, byte[]> bodies) {}

  /** A verify-all repo with {@link #KEYS} registered, and the six bodies of a deploy. */
  private Fixture fixture() throws Exception {
    final var repo = this.mavenRepo(true);
    final var admin = this.admin();
    this.registerPublicKey(repo, admin, KEYS);

    return new Fixture(repo, admin, bodiesOf(repo));
  }

  private static Map<String, byte[]> bodiesOf(final Repo repo) {
    final var pom = pom("1.0");
    final var jar = ("jar of " + repo.getName()).getBytes(UTF_8);
    final var sources = ("sources of " + repo.getName()).getBytes(UTF_8);

    return Map.of(
        POM,
        pom,
        JAR,
        jar,
        SOURCES,
        sources,
        POM + ".asc",
        sign(pom),
        JAR + ".asc",
        sign(jar),
        SOURCES + ".asc",
        sign(sources));
  }

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

  private static byte[] sign(final byte[] file) {
    return KEYS.detachedSignature(file).getBytes(UTF_8);
  }

  private static Path stored(final Repo repo, final String path) {
    return storageDirOf(repo).resolve(path);
  }

  private List<Boolean> signedOf(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select v.signed from maven_artifact_version v
          join maven_artifact a on a.id = v.artifact_id
         where a.repo_id = ? and a.artifact_name = 'lib' and v.version_name = '1.0'""",
        Boolean.class,
        repo.getId());
  }

  private List<String> verifiedFiles(final Repo repo) {
    return this.jdbcTemplate.queryForList(
        """
        select s.file_name from maven_version_signature s
          join maven_artifact_version v on v.id = s.artifact_version_id
          join maven_artifact a on a.id = v.artifact_id
         where a.repo_id = ? order by s.file_name""",
        String.class,
        repo.getId());
  }

  private int pendingCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_pending_signature where repo_id = ?",
        Integer.class,
        repo.getId());
  }

  private int artifactCount(final Repo repo) {
    return this.jdbcTemplate.queryForObject(
        "select count(*) from maven_artifact where repo_id = ?", Integer.class, repo.getId());
  }

  /** Every request answered 200 and the deploy ended complete, whatever its order. */
  private void assertDeployComplete(final Fixture f) throws Exception {
    for (final var path : SIX) {
      assertThat(Files.readAllBytes(stored(f.repo(), path)))
          .as("stored %s", path)
          .isEqualTo(f.bodies().get(path));
    }

    assertThat(this.pendingCount(f.repo())).as("parked signatures left").isZero();
    assertThat(this.verifiedFiles(f.repo()))
        .containsExactlyInAnyOrder("lib-1.0-sources.jar", "lib-1.0.jar", "lib-1.0.pom");
    assertThat(this.signedOf(f.repo())).containsExactly(true);
    assertThat(KEY_SERVER_REQUESTS.get()).as("key server requests").isZero();
  }

  private void deployInOrder(final Fixture f, final List<String> order) throws Exception {
    for (final var path : order) {
      assertThat(this.status(f.repo(), f.admin(), path, f.bodies().get(path)))
          .as("PUT %s in the order %s", path, order)
          .isEqualTo(200);
    }

    this.assertDeployComplete(f);
  }

  @Test
  @DisplayName(
      "the named orders of a deploy all end complete: files first, signatures first, POM last")
  void theNamedOrdersEndComplete() throws Exception {
    final var orders =
        List.of(
            List.of(POM, JAR, SOURCES, POM + ".asc", JAR + ".asc", SOURCES + ".asc"),
            List.of(POM + ".asc", JAR + ".asc", SOURCES + ".asc", POM, JAR, SOURCES),
            List.of(JAR, SOURCES, POM, JAR + ".asc", SOURCES + ".asc", POM + ".asc"),
            List.of(JAR + ".asc", SOURCES + ".asc", POM + ".asc", JAR, SOURCES, POM),
            List.of(POM, POM + ".asc", JAR + ".asc", SOURCES + ".asc", JAR, SOURCES),
            List.of(SOURCES + ".asc", JAR, POM + ".asc", POM, SOURCES, JAR + ".asc"));

    for (final var order : orders) {
      this.deployInOrder(this.fixture(), order);
    }
  }

  @Test
  @DisplayName("a seeded sample of the 720 orders of the six requests all end complete")
  void aSampleOfAllOrdersEndsComplete() throws Exception {
    final var random = new Random(1188);

    for (int i = 0; i < 40; i++) {
      final var order = new ArrayList<>(SIX);
      Collections.shuffle(order, random);

      this.deployInOrder(this.fixture(), order);
    }
  }

  @Test
  @DisplayName("a parked signature is neither served nor charged until it verifies")
  void aParkedSignatureIsNotServedNorCharged() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));

    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);
    assertThat(this.reportedUsage())
        .containsExactly(new UsageChangedInfo(f.repo().getId(), diskOf(f.bodies().get(POM))));

    this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));

    final var signature = f.bodies().get(JAR + ".asc");
    assertThat(this.reportedUsage())
        .containsExactlyInAnyOrder(
            new UsageChangedInfo(f.repo().getId(), diskOf(f.bodies().get(POM))),
            new UsageChangedInfo(f.repo().getId(), diskOf(f.bodies().get(JAR))),
            new UsageChangedInfo(f.repo().getId(), diskOf(signature)));
  }

  private static io.repsy.libs.storage.core.dtos.BaseUsages diskOf(final byte[] file) {
    return io.repsy.libs.storage.core.dtos.BaseUsages.ofDisk(file.length);
  }

  private List<UsageChangedInfo> reportedUsage() {
    return mockingDetails(this.usageUpdateService).getInvocations().stream()
        .filter(invocation -> invocation.getMethod().getName().equals("updateUsage"))
        .map(invocation -> invocation.<UsageChangedInfo>getArgument(0))
        .toList();
  }

  @Test
  @DisplayName(
      "a parked signature that does not verify fails the file's upload with 422 and takes the file back")
  void aBadParkedSignatureFailsTheFile() throws Exception {
    final var f = this.fixture();
    final var wrong = sign("some other jar".getBytes(UTF_8));
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", wrong);
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);

    expectError(
        this.upload(f.repo(), f.admin(), JAR, f.bodies().get(JAR)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pendingSignatureNotVerified",
        "pendingSignatureNotVerified",
        "The signature uploaded earlier for this file does not verify against it; upload both"
            + " again.");

    assertThat(stored(f.repo(), JAR)).doesNotExist();
    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.pendingCount(f.repo())).isZero();
    assertThat(this.verifiedFiles(f.repo())).isEmpty();

    // A retry starts clean.
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));
    this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));
    assertThat(this.verifiedFiles(f.repo())).containsExactly("lib-1.0.jar");
  }

  @Test
  @DisplayName(
      "a bad parked signature of a jar that arrived before the POM fails the POM, registering nothing")
  void aBadParkedSignatureFailsThePomBeforeItRegistersAnything() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign("some other jar".getBytes(UTF_8)));

    expectError(
        this.upload(f.repo(), f.admin(), POM, f.bodies().get(POM)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "pendingSignatureNotVerified",
        "pendingSignatureNotVerified",
        "The signature uploaded earlier for this file does not verify against it; upload both"
            + " again.");

    assertThat(stored(f.repo(), POM)).doesNotExist();
    assertThat(this.artifactCount(f.repo())).isZero();
    assertThat(this.pendingCount(f.repo())).isZero();
  }

  @Test
  @DisplayName(
      "garbage that is not a signature is refused at once, even before its file, and parks nothing")
  void garbageBeforeItsFileIsRefusedAtOnce() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));

    expectError(
        this.upload(
            f.repo(),
            f.admin(),
            JAR + ".asc",
            "-----BEGIN PGP SIGNATURE-----\n\n-----END PGP SIGNATURE-----\n".getBytes(UTF_8)),
        HttpStatus.UNPROCESSABLE_ENTITY,
        "artifactSignatureNotVerified",
        "artifactSignatureNotVerified",
        "Artifact signature isn't verified!");

    assertThat(this.pendingCount(f.repo())).isZero();
    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
  }

  @Test
  @DisplayName("a signature is re-sent while parked: the latest one is the one verified")
  void aResentParkedSignatureReplacesTheFirst() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", sign("some other jar".getBytes(UTF_8)));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));

    assertThat(this.pendingCount(f.repo())).isEqualTo(1);

    this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));

    assertThat(Files.readAllBytes(stored(f.repo(), JAR + ".asc")))
        .isEqualTo(f.bodies().get(JAR + ".asc"));
    assertThat(this.pendingCount(f.repo())).isZero();
  }

  @Test
  @DisplayName(
      "with the lookup off an unregistered signer fails the file with 404 and keeps the signature")
  void anUnregisteredSignerWithTheLookupOffFailsTheFileAndKeepsTheRow() throws Exception {
    final var repo = this.mavenRepo(true);
    final var managed = this.repoRepository.findByName(repo.getName()).orElseThrow();
    managed.setPgpKeyServerLookupEnabled(false);
    this.repoRepository.saveAndFlush(managed);
    final var admin = this.admin();
    final var bodies = bodiesOf(repo);
    this.uploadOk(repo, admin, POM, bodies.get(POM));
    this.uploadOk(repo, admin, JAR + ".asc", bodies.get(JAR + ".asc"));

    expectError(
        this.upload(repo, admin, JAR, bodies.get(JAR)),
        HttpStatus.NOT_FOUND,
        "artifactSigningKeyNotRegistered",
        "artifactSigningKeyNotRegistered",
        "The key that signed this artifact is not registered, and key-server lookup is off.");

    assertThat(stored(repo, JAR)).doesNotExist();
    assertThat(this.pendingCount(repo)).isEqualTo(1);
    assertThat(KEY_SERVER_REQUESTS.get()).isZero();

    // Register the key and send the file again: the parked signature now verifies.
    this.registerPublicKey(repo, admin, KEYS);
    this.uploadOk(repo, admin, JAR, bodies.get(JAR));

    assertThat(Files.readAllBytes(stored(repo, JAR + ".asc"))).isEqualTo(bodies.get(JAR + ".asc"));
    assertThat(this.pendingCount(repo)).isZero();
  }

  @Test
  @DisplayName(
      "a repo that does not verify every signature still refuses a signature before its file")
  void withoutVerifyAllASignatureBeforeItsFileIsStillRefused() throws Exception {
    final var repo = this.mavenRepo(false);
    final var admin = this.admin();
    this.registerPublicKey(repo, admin, KEYS);
    final var bodies = bodiesOf(repo);

    expectError(
        this.upload(repo, admin, POM + ".asc", bodies.get(POM + ".asc")),
        HttpStatus.NOT_FOUND,
        "itemNotFound",
        "itemNotFound",
        "The requested item is not found.");

    assertThat(this.pendingCount(repo)).isZero();
  }

  @Test
  @DisplayName("a signature of a stored POM whose version row is gone is parked when verifying all")
  void aPomSignatureOfAStoredUnregisteredPomIsParked() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.jdbcTemplate.update("delete from maven_artifact where repo_id = ?", f.repo().getId());

    this.uploadOk(f.repo(), f.admin(), POM + ".asc", f.bodies().get(POM + ".asc"));

    assertThat(this.pendingCount(f.repo())).isEqualTo(1);
    assertThat(stored(f.repo(), POM + ".asc")).doesNotExist();
  }

  @Test
  @DisplayName("deleting the version, or the repo, drops the signatures parked under it")
  void deletingDropsTheParkedSignatures() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);

    final var result =
        this.mockMvc
            .perform(
                delete("/api/mvn/artifacts/{repo}/com.acme/lib/versions/1.0", f.repo().getName())
                    .with(apiPort())
                    .header(AUTHORIZATION, this.bearerTokenFor(f.admin())))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    assertThat(this.pendingCount(f.repo())).isZero();
  }

  @Test
  @DisplayName("the purge deletes the signatures older than the ttl and nothing else")
  void thePurgeDeletesTheOldOnes() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));
    this.uploadOk(f.repo(), f.admin(), SOURCES + ".asc", f.bodies().get(SOURCES + ".asc"));
    this.jdbcTemplate.update(
        "update maven_pending_signature set created_at = ? where repo_id = ?"
            + " and signed_file_path like '%sources%'",
        Timestamp.from(Instant.now().minus(Duration.ofHours(25))), f.repo().getId());

    final var purged = this.pendingSignatureService.purgeOlderThan(Duration.ofHours(24));

    assertThat(purged).isGreaterThanOrEqualTo(1);
    assertThat(
            this.jdbcTemplate.queryForList(
                "select signed_file_path from maven_pending_signature where repo_id = ?",
                String.class,
                f.repo().getId()))
        .containsExactly(JAR);
  }

  @Test
  @DisplayName(
      "turning verify-all off while a signature is parked leaves it untouched, and the file stored")
  void turningVerifyAllOffLeavesTheParkedSignature() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));
    final var managed = this.repoRepository.findByName(f.repo().getName()).orElseThrow();
    managed.setPgpVerifyAllSignaturesEnabled(false);
    this.repoRepository.saveAndFlush(managed);

    this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));

    assertThat(stored(f.repo(), JAR)).exists();
    assertThat(stored(f.repo(), JAR + ".asc")).doesNotExist();
    assertThat(this.pendingCount(f.repo())).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "a file that lands while its signature is being parked still ends with the signature verified")
  void aFileLandingWhileTheSignatureIsParkedIsNotMissed() throws Exception {
    final var f = this.fixture();
    this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
    final var pool = Executors.newSingleThreadExecutor();

    try {
      // The jar is uploaded in full after the signature request saw it missing, and before that
      // request commits its parked row: the signature request has to look again to find it.
      doAnswer(
              invocation -> {
                pool.submit(
                        () -> {
                          this.uploadOk(f.repo(), f.admin(), JAR, f.bodies().get(JAR));

                          return null;
                        })
                    .get(30, TimeUnit.SECONDS);

                return invocation.callRealMethod();
              })
          .when(this.pgpVerifierService)
          .readSignerKeyId(any());

      this.uploadOk(f.repo(), f.admin(), JAR + ".asc", f.bodies().get(JAR + ".asc"));
    } finally {
      pool.shutdownNow();
    }

    assertThat(Files.readAllBytes(stored(f.repo(), JAR + ".asc")))
        .isEqualTo(f.bodies().get(JAR + ".asc"));
    assertThat(this.pendingCount(f.repo())).isZero();
    assertThat(this.verifiedFiles(f.repo())).containsExactly("lib-1.0.jar");
  }

  @Test
  @DisplayName("the six requests of a deploy sent at the same time, 15 times, all end complete")
  void aParallelDeployEndsComplete() throws Exception {
    for (int run = 0; run < 15; run++) {
      final var f = this.fixture();
      this.uploadOk(f.repo(), f.admin(), POM, f.bodies().get(POM));
      final var rest = new ArrayList<>(SIX);
      rest.remove(POM);
      final var pool = Executors.newFixedThreadPool(rest.size());
      final var start = new CountDownLatch(1);

      try {
        final List<Future<Integer>> answers = new ArrayList<>();

        for (final var path : rest) {
          answers.add(
              pool.submit(
                  () -> {
                    start.await();

                    return this.status(f.repo(), f.admin(), path, f.bodies().get(path));
                  }));
        }

        start.countDown();

        for (final var answer : answers) {
          assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
      } finally {
        pool.shutdownNow();
      }

      this.assertDeployComplete(f);
    }
  }

  @Test
  @DisplayName("all six requests sent at the same time, POM included, end complete")
  void aFullyParallelDeployIncludingThePomEndsComplete() throws Exception {
    for (int run = 0; run < 10; run++) {
      final var f = this.fixture();
      final var pool = Executors.newFixedThreadPool(SIX.size());
      final var start = new CountDownLatch(1);

      try {
        final List<Future<Integer>> answers = new ArrayList<>();

        for (final var path : SIX) {
          answers.add(
              pool.submit(
                  () -> {
                    start.await();

                    return this.status(f.repo(), f.admin(), path, f.bodies().get(path));
                  }));
        }

        start.countDown();

        for (final var answer : answers) {
          assertThat(answer.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
      } finally {
        pool.shutdownNow();
      }

      this.assertDeployComplete(f);
    }
  }
}
