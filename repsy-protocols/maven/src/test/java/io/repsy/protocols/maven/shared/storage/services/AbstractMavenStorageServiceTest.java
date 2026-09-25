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
package io.repsy.protocols.maven.shared.storage.services;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import freemarker.template.Configuration;
import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.libs.storage.core.exceptions.IsADirectoryException;
import io.repsy.libs.storage.core.services.StorageStrategy;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/**
 * RPS-1190: a version delete used to fail {@code Files.move} with {@code NoSuchFileException} when
 * the storage directory was already gone (an earlier partial delete, a manual cleanup, a wrong
 * version name reaching storage before the DB check). {@link
 * AbstractMavenStorageService#deleteArtifactVersion} is idempotent now: a missing directory is
 * treated as already deleted instead of failing.
 *
 * <p>RPS-1197: rewriting {@code maven-metadata.xml} (on a version delete) left a stale {@code
 * maven-metadata.xml.asc} and its checksum siblings behind, signing content that no longer matches.
 * They are deleted along with the rewrite now, and the bytes they freed are folded into the
 * returned usage delta.
 *
 * <p>RPS-1437: {@link AbstractMavenStorageService#addVersionsToMetadata} adds the registered
 * versions a stored {@code maven-metadata.xml} lacks, with the same rewrite, and the rewrite, a
 * delete's and a client's own upload of the file are serialized per artifact.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractMavenStorageService")
class AbstractMavenStorageServiceTest {

  private static final UUID REPO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
  private static final String REPO_NAME = "maven-repo";
  private static final String GROUP = "com.example";
  private static final String ARTIFACT = "demo";
  private static final String METADATA_FILENAME = "maven-metadata.xml";

  private static final String METADATA_XML =
      """
      <metadata>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <versioning>
          <latest>2.0</latest>
          <release>2.0</release>
          <versions><version>1.0</version><version>2.0</version></versions>
          <lastUpdated>20260101000000</lastUpdated>
        </versioning>
      </metadata>
      """;

  @Mock private StorageStrategy storageStrategy;
  @Mock private Configuration freeMarkerConfiguration;

  private AbstractMavenStorageService<UUID> storageService;

  private static ArgumentMatcher<StoragePath> pathEndingWith(final String fileName) {
    return storagePath -> storagePath != null && storagePath.getPath().endsWith(fileName);
  }

  @BeforeEach
  void setUp() {
    this.storageService =
        new AbstractMavenStorageService<>(this.freeMarkerConfiguration, this.storageStrategy) {};
  }

  @Test
  @DisplayName("deleteArtifactVersion frees nothing when the version directory is already gone")
  void deleteArtifactVersionIsIdempotentWhenDirectoryMissing() {

    // The storage strategy's delete is idempotent, and a directory that is gone has no usage.
    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(0L);

    final var usage = this.storageService.deleteArtifactVersion(REPO_ID, GROUP, ARTIFACT, "1.0");

    assertThat(usage).isZero();
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo/1.0")));
  }

  @Test
  @DisplayName("deleteGroup succeeds when the group directory is already gone (RPS-1290)")
  void deleteGroupIsIdempotentWhenDirectoryMissing() {

    when(this.storageStrategy.listDirectoryContents(any()))
        .thenThrow(new ItemNotFoundException("resourceNotFound"));
    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(0L);

    final var usage = this.storageService.deleteGroup(REPO_ID, GROUP, List.of(ARTIFACT));

    assertThat(usage).isZero();
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo")));
  }

  @Test
  @DisplayName("deleteArtifactVersion deletes the directory and returns its usage when present")
  void deleteArtifactVersionDeletesExistingDirectory() {

    when(this.storageStrategy.calculatePathUsage(any())).thenReturn(4096L);

    final var usage = this.storageService.deleteArtifactVersion(REPO_ID, GROUP, ARTIFACT, "1.0");

    assertThat(usage).isEqualTo(4096L);
    verify(this.storageStrategy).delete(argThat(pathEndingWith("com/example/demo/1.0")));
  }

  @Test
  @DisplayName(
      "deleting a version's metadata also deletes a stale maven-metadata.xml.asc family and folds"
          + " the freed bytes into the usage delta")
  void deleteVersionFromMetadataRemovesStaleSignatureFamily() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();

    final var ascResource = new ByteArrayResource(new byte[100]);
    final var ascSha1Resource = new ByteArrayResource(new byte[40]);

    when(this.storageStrategy.get(any(), anyString())).thenReturn(Optional.empty());
    when(this.storageStrategy.get(argThat(pathEndingWith(METADATA_FILENAME)), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource(METADATA_XML.getBytes(UTF_8))));
    when(this.storageStrategy.get(argThat(pathEndingWith(METADATA_FILENAME + ".asc")), anyString()))
        .thenReturn(Optional.of(ascResource));
    when(this.storageStrategy.get(
            argThat(pathEndingWith(METADATA_FILENAME + ".asc.sha1")), anyString()))
        .thenReturn(Optional.of(ascSha1Resource));
    when(this.storageStrategy.write(anyString(), any(), any())).thenReturn(BaseUsages.ofDisk(-20L));

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    // -20 (metadata rewrite delta) - 100 (.asc) - 40 (.asc.sha1) = -160.
    assertThat(result.getDiskUsage()).isEqualTo(-160L);

    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc")));
    verify(this.storageStrategy).delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.sha1")));
    verify(this.storageStrategy, never())
        .delete(argThat(pathEndingWith(METADATA_FILENAME + ".asc.md5")));
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of an artifact that has no maven-metadata.xml is nothing to"
          + " rewrite: zero usage, no file written or deleted, no exception (RPS-1331)")
  void deleteVersionFromMetadataWithoutMetadataFileIsANoOp() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString())).thenReturn(Optional.empty());

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    assertThat(result.getDiskUsage()).isZero();
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of a file without <versioning> leaves the file alone and"
          + " answers zero usage instead of a NullPointerException (RPS-1331)")
  void deleteVersionFromMetadataWithoutVersioningIsANoOp() throws Exception {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource("<metadata/>".getBytes(UTF_8))));

    final var result =
        this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0");

    assertThat(result.getDiskUsage()).isZero();
    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName(
      "deleting a version's metadata of a file that cannot be parsed fails before anything is"
          + " written or deleted (RPS-1331)")
  void deleteVersionFromMetadataWithUnparsableFileChangesNothing() {

    final var repoInfo = BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
    when(this.storageStrategy.get(any(), anyString()))
        .thenReturn(Optional.of(new ByteArrayResource("<metadata><versioning>".getBytes(UTF_8))));

    assertThatThrownBy(
            () -> this.storageService.deleteVersionFromMetadata(repoInfo, GROUP, ARTIFACT, "1.0"))
        .isInstanceOf(BadRequestException.class);

    verify(this.storageStrategy, never()).write(anyString(), any(), any());
    verify(this.storageStrategy, never()).delete(any());
  }

  @Test
  @DisplayName("exists tells a stored file, a missing one and a directory (RPS-1199)")
  void existsTellsStoredMissingAndDirectory() {
    final var pom = StoragePath.of(REPO_ID, "com/example/demo/1.0/demo-1.0.pom");
    final var missing = StoragePath.of(REPO_ID, "com/example/demo/2.0/demo-2.0.pom");
    final var directory = StoragePath.of(REPO_ID, "com/example/demo/3.0/demo-3.0.pom");
    when(this.storageStrategy.get(pom, REPO_NAME))
        .thenReturn(Optional.of(new ByteArrayResource("<project/>".getBytes(UTF_8))));
    when(this.storageStrategy.get(missing, REPO_NAME)).thenReturn(Optional.empty());
    when(this.storageStrategy.get(directory, REPO_NAME)).thenThrow(new IsADirectoryException());

    assertThat(this.storageService.exists(pom, REPO_NAME)).isTrue();
    assertThat(this.storageService.exists(missing, REPO_NAME)).isFalse();
    assertThat(this.storageService.exists(directory, REPO_NAME)).isTrue();
  }

  @Test
  @DisplayName("deleteFile soft-deletes only the one file (RPS-1199)")
  void deleteFileSoftDeletesOnlyTheFile() {
    final var pom = StoragePath.of(REPO_ID, "com/example/demo/1.0/demo-1.0.pom");

    this.storageService.deleteFile(pom);

    verify(this.storageStrategy).delete(pom);
    verify(this.storageStrategy, never()).calculatePathUsage(any());
    verify(this.storageStrategy, never()).listDirectoryContents(any());
  }

  private static final String METADATA_PATH = "com/example/demo/" + METADATA_FILENAME;

  /** What the fake storage holds, by path. Reads and writes go through it, in call order. */
  private final Map<String, byte[]> files = new TreeMap<>();

  private final List<String> writes = new ArrayList<>();
  private final List<String> deletes = new ArrayList<>();

  private BaseRepoInfo<UUID> repoInfo() {
    return BaseRepoInfo.<UUID>builder().storageKey(REPO_ID).name(REPO_NAME).build();
  }

  /** Makes the mocked storage strategy a map: what is written is what a later get answers. */
  private void useInMemoryStorage() {
    lenient()
        .when(this.storageStrategy.get(any(), anyString()))
        .thenAnswer(
            invocation -> {
              final var content =
                  this.files.get(
                      invocation.<StoragePath>getArgument(0).getRelativePath().getPath());

              return Optional.ofNullable(content).<Resource>map(ByteArrayResource::new);
            });
    lenient()
        .when(this.storageStrategy.write(anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              final var path = invocation.<StoragePath>getArgument(1).getRelativePath().getPath();
              final var bytes = invocation.<InputStream>getArgument(2).readAllBytes();
              final var before = this.files.get(path);

              synchronized (this.writes) {
                this.writes.add(path);
              }

              this.files.put(path, bytes);

              return BaseUsages.ofDisk(bytes.length - (before == null ? 0 : before.length));
            });
    lenient()
        .doAnswer(
            invocation -> {
              final var path = invocation.<StoragePath>getArgument(0).getRelativePath().getPath();

              this.deletes.add(path);
              this.files.remove(path);

              return null;
            })
        .when(this.storageStrategy)
        .delete(any());
  }

  private void store(final String fileName, final String content) {
    this.files.put("com/example/demo/" + fileName, content.getBytes(UTF_8));
  }

  private String stored(final String fileName) {
    return new String(this.files.get("com/example/demo/" + fileName), UTF_8);
  }

  private Metadata storedMetadata() throws Exception {
    return new MetadataXpp3Reader()
        .read(new ByteArrayInputStream(this.files.get(METADATA_PATH)), false);
  }

  private static Supplier<Collection<String>> registered(final String... versions) {
    return () -> List.of(versions);
  }

  @Test
  @DisplayName("adding versions to an artifact without a stored metadata file creates nothing")
  void addVersionsWithoutMetadataFileIsANoOp() throws Exception {
    useInMemoryStorage();
    final var asked = new AtomicBoolean();

    final var delta =
        this.storageService.addVersionsToMetadata(
            repoInfo(),
            GROUP,
            ARTIFACT,
            () -> {
              asked.set(true);

              return List.of("1.0");
            });

    assertThat(delta).isZero();
    assertThat(asked).as("no version is asked for when there is no file").isFalse();
    assertThat(this.writes).isEmpty();
    assertThat(this.files).isEmpty();
  }

  @Test
  @DisplayName("adding versions leaves a file that lists them all byte for byte and keeps its .asc")
  void addVersionsAlreadyListedWritesNothing() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    store(METADATA_FILENAME + ".asc", "signature");
    store(METADATA_FILENAME + ".sha1", "stale");

    final var delta =
        this.storageService.addVersionsToMetadata(
            repoInfo(), GROUP, ARTIFACT, registered("2.0", "1.0"));

    assertThat(delta).isZero();
    assertThat(this.writes).isEmpty();
    assertThat(this.deletes).isEmpty();
    assertThat(stored(METADATA_FILENAME)).isEqualTo(METADATA_XML);
    assertThat(stored(METADATA_FILENAME + ".asc")).isEqualTo("signature");
    assertThat(stored(METADATA_FILENAME + ".sha1")).isEqualTo("stale");
  }

  @Test
  @DisplayName(
      "adding a version the file lacks sorts the versions, moves latest and release, stamps"
          + " lastUpdated, rewrites only the stored checksums, drops the .asc family and counts every"
          + " byte")
  void addVersionsAddsTheMissingVersion() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    // A checksum file with a trailing newline is 1 byte longer than the digest that replaces it.
    store(METADATA_FILENAME + ".sha1", DigestUtils.sha1Hex(METADATA_XML) + "\n");
    store(METADATA_FILENAME + ".md5", "stale");
    store(METADATA_FILENAME + ".asc", "x".repeat(100));
    store(METADATA_FILENAME + ".asc.sha1", "y".repeat(40));
    final var before = this.files.get(METADATA_PATH).length;

    final var delta =
        this.storageService.addVersionsToMetadata(
            repoInfo(), GROUP, ARTIFACT, registered("3.0", "1.0", "2.0"));

    final var metadata = storedMetadata();
    final var versioning = metadata.getVersioning();

    assertThat(versioning.getVersions()).containsExactly("1.0", "2.0", "3.0");
    assertThat(versioning.getLatest()).isEqualTo("3.0");
    assertThat(versioning.getRelease()).isEqualTo("3.0");
    assertThat(versioning.getLastUpdated()).isNotEqualTo("20260101000000");
    assertThat(metadata.getGroupId()).isEqualTo(GROUP);
    assertThat(metadata.getArtifactId()).isEqualTo(ARTIFACT);

    final var xml = this.files.get(METADATA_PATH);

    assertThat(stored(METADATA_FILENAME + ".sha1")).isEqualTo(DigestUtils.sha1Hex(xml));
    assertThat(stored(METADATA_FILENAME + ".md5")).isEqualTo(DigestUtils.md5Hex(xml));
    assertThat(this.files).doesNotContainKey("com/example/demo/" + METADATA_FILENAME + ".sha256");
    assertThat(this.files).doesNotContainKey("com/example/demo/" + METADATA_FILENAME + ".asc");
    assertThat(this.files).doesNotContainKey("com/example/demo/" + METADATA_FILENAME + ".asc.sha1");
    assertThat(this.deletes).hasSize(2);

    // The xml grew, the sha1 lost its newline (-1), the md5 grew from "stale" (5) to 32, and 140
    // bytes of signatures were freed.
    assertThat(delta).isEqualTo((xml.length - before) + (-1) + (32 - 5) - 140);
  }

  @Test
  @DisplayName("adding a snapshot version moves latest but leaves release on the last release")
  void addVersionsAddsASnapshotAsLatestOnly() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);

    this.storageService.addVersionsToMetadata(
        repoInfo(), GROUP, ARTIFACT, registered("1.0", "2.0", "3.0-SNAPSHOT"));

    final var versioning = storedMetadata().getVersioning();

    assertThat(versioning.getVersions()).containsExactly("1.0", "2.0", "3.0-SNAPSHOT");
    assertThat(versioning.getLatest()).isEqualTo("3.0-SNAPSHOT");
    assertThat(versioning.getRelease()).isEqualTo("2.0");

    final var written = this.writes.size();

    this.storageService.addVersionsToMetadata(
        repoInfo(), GROUP, ARTIFACT, registered("1.0", "2.0", "3.0-SNAPSHOT"));

    assertThat(this.writes).as("the second append has nothing to add").hasSize(written);
  }

  @Test
  @DisplayName("adding versions never removes one the file lists but the repository does not know")
  void addVersionsKeepsAVersionThatIsNotRegistered() throws Exception {
    useInMemoryStorage();
    store(
        METADATA_FILENAME,
        METADATA_XML.replace("<version>2.0</version>", "<version>9.9</version>"));

    this.storageService.addVersionsToMetadata(
        repoInfo(), GROUP, ARTIFACT, registered("1.0", "3.0"));

    final var versioning = storedMetadata().getVersioning();

    assertThat(versioning.getVersions()).containsExactly("1.0", "3.0", "9.9");
    assertThat(versioning.getLatest()).isEqualTo("9.9");
  }

  @Test
  @DisplayName("adding versions to a file that cannot be parsed fails before anything is written")
  void addVersionsWithUnparsableFileChangesNothing() {
    useInMemoryStorage();
    store(METADATA_FILENAME, "<metadata><versioning>");
    store(METADATA_FILENAME + ".asc", "signature");
    final var asked = new AtomicBoolean();

    assertThatThrownBy(
            () ->
                this.storageService.addVersionsToMetadata(
                    repoInfo(),
                    GROUP,
                    ARTIFACT,
                    () -> {
                      asked.set(true);

                      return List.of("1.0");
                    }))
        .isInstanceOf(BadRequestException.class);

    assertThat(asked).isFalse();
    assertThat(this.writes).isEmpty();
    assertThat(this.deletes).isEmpty();
    assertThat(stored(METADATA_FILENAME)).isEqualTo("<metadata><versioning>");
  }

  @Test
  @DisplayName("adding versions to a file without <versioning> leaves it alone")
  void addVersionsWithoutVersioningIsANoOp() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, "<metadata/>");
    final var asked = new AtomicBoolean();

    final var delta =
        this.storageService.addVersionsToMetadata(
            repoInfo(),
            GROUP,
            ARTIFACT,
            () -> {
              asked.set(true);

              return List.of("1.0");
            });

    assertThat(delta).isZero();
    assertThat(asked).isFalse();
    assertThat(this.writes).isEmpty();
    assertThat(stored(METADATA_FILENAME)).isEqualTo("<metadata/>");
  }

  @Test
  @DisplayName("a delete's rewrite counts the change of the checksum files it rewrites (RPS-1437)")
  void deleteVersionFromMetadataCountsTheChecksumDelta() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    store(METADATA_FILENAME + ".sha1", "z".repeat(60));
    final var before = this.files.get(METADATA_PATH).length;

    final var usage =
        this.storageService.deleteVersionFromMetadata(repoInfo(), GROUP, ARTIFACT, "1.0");

    final var xml = this.files.get(METADATA_PATH);

    assertThat(usage.getDiskUsage()).isEqualTo((xml.length - before) + (40 - 60));
    assertThat(stored(METADATA_FILENAME + ".sha1")).isEqualTo(DigestUtils.sha1Hex(xml));
  }

  /** Runs an append that stays inside the artifact's lock until {@code release} is counted down. */
  private CompletableFuture<Long> appendHeldUntil(
      final CountDownLatch inside, final CountDownLatch release, final String version) {

    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return this.storageService.addVersionsToMetadata(
                repoInfo(),
                GROUP,
                ARTIFACT,
                () -> {
                  inside.countDown();

                  try {
                    release.await(10, TimeUnit.SECONDS);
                  } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }

                  return List.of("1.0", "2.0", version);
                });
          } catch (final java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
        });
  }

  @Test
  @DisplayName("a client's own upload of the artifact's metadata waits for a running append")
  void clientUploadOfTheMetadataWaitsForAnAppend() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    final var inside = new CountDownLatch(1);
    final var release = new CountDownLatch(1);

    final var append = appendHeldUntil(inside, release, "3.0");
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

    final var clientBytes =
        "<metadata><versioning><versions><version>1.0</version></versions></versioning></metadata>";
    final var upload =
        CompletableFuture.runAsync(
            () ->
                this.storageService.writeInputStreamToPath(
                    StoragePath.of(REPO_ID, METADATA_PATH),
                    new ByteArrayInputStream(clientBytes.getBytes(UTF_8)),
                    REPO_NAME));

    // The upload has all the time to get past the lock, and does not.
    TimeUnit.MILLISECONDS.sleep(300);
    assertThat(upload).isNotDone();
    assertThat(this.writes).isEmpty();

    release.countDown();
    append.get(10, TimeUnit.SECONDS);
    upload.get(10, TimeUnit.SECONDS);

    // The append wrote first, the client's file is the last word.
    assertThat(this.writes).containsExactly(METADATA_PATH, METADATA_PATH);
    assertThat(stored(METADATA_FILENAME)).isEqualTo(clientBytes);
  }

  @Test
  @DisplayName("a delete's rewrite waits for a running append and rewrites what it wrote")
  void deleteRewriteWaitsForAnAppend() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    final var inside = new CountDownLatch(1);
    final var release = new CountDownLatch(1);

    final var append = appendHeldUntil(inside, release, "3.0");
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

    final var delete =
        CompletableFuture.runAsync(
            () -> {
              try {
                this.storageService.deleteVersionFromMetadata(repoInfo(), GROUP, ARTIFACT, "1.0");
              } catch (final Exception e) {
                throw new IllegalStateException(e);
              }
            });

    TimeUnit.MILLISECONDS.sleep(300);
    assertThat(delete).isNotDone();

    release.countDown();
    append.get(10, TimeUnit.SECONDS);
    delete.get(10, TimeUnit.SECONDS);

    // Without the lock the delete would have read the file before the append and written it back
    // without 3.0.
    assertThat(storedMetadata().getVersioning().getVersions()).containsExactly("2.0", "3.0");
  }

  @Test
  @DisplayName(
      "the upload of a checksum, another artifact's metadata or any other file is not locked")
  void otherUploadsAreNotLocked() throws Exception {
    useInMemoryStorage();
    store(METADATA_FILENAME, METADATA_XML);
    final var inside = new CountDownLatch(1);
    final var release = new CountDownLatch(1);

    final var append = appendHeldUntil(inside, release, "3.0");
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

    final var bytes = new ByteArrayInputStream("x".getBytes(UTF_8));

    // Done on this thread: it would hang here, and fail the test after the latch timeout, if
    // locked.
    this.storageService.writeInputStreamToPath(
        StoragePath.of(REPO_ID, METADATA_PATH + ".sha1"), bytes, REPO_NAME);
    this.storageService.writeInputStreamToPath(
        StoragePath.of(REPO_ID, "com/example/demo/1.0/demo-1.0.pom"),
        new ByteArrayInputStream("pom".getBytes(UTF_8)),
        REPO_NAME);

    assertThat(append).isNotDone();

    release.countDown();
    append.get(10, TimeUnit.SECONDS);
  }
}
