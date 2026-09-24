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
package io.repsy.protocols.maven.shared.artifact.services.contracts;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.maven.shared.artifact.dtos.SignatureOutcome;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
public interface ArtifactService<ID> {

  /**
   * Classifies an upload of a file that is not a {@code maven-metadata.xml}.
   *
   * <p>A checksum is judged by the file it belongs to: it is refused when the path of that file is
   * not a Maven 2 artifact path, and it carries the version type of that file (RPS-1183).
   *
   * @throws BadRequestException {@code invalidArtifactPath} when the path is not a Maven 2 artifact
   *     path (metadata is not judged here)
   */
  ArtifactVersionType getVersionType(BaseRepoInfo<ID> repoInfo, StoragePath storagePath);

  /**
   * Classifies an upload of a {@code maven-metadata.xml}, of one of its checksums or of its {@code
   * .asc} signature. A metadata checksum or signature holds a hash or armored text, not XML, so it
   * is judged by its directory: a file of a {@code SNAPSHOT} version directory is a snapshot, any
   * other level is not judged (RPS-1183, RPS-1185).
   */
  @Nullable ArtifactVersionType getVersionTypeByMetadataTypeFiles(
      BaseRepoInfo<ID> repoInfo, byte[] content, StoragePath storagePath);

  /**
   * Refuses an upload that the repo settings do not allow.
   *
   * @param versionType what the classification above returned; {@code null} for a file that carries
   *     no version type, which the {@code releases} and {@code snapshots} rule does not judge
   */
  void checkDeploymentRules(
      BaseRepoInfo<ID> repoInfo,
      @Nullable ArtifactVersionType versionType,
      StoragePath storagePath);

  void createOrUpdateArtifact(
      BaseRepoInfo<ID> repoInfo, StoragePath storagePath, Resource resource);

  StoragePath getNonSignedStoragePath(StoragePath storagePath);

  /**
   * Verifies a detached signature of a file that is already stored, or, on a repo that verifies
   * every signature, keeps it back until that file arrives (RPS-1188). Nothing is written or
   * deleted in the repository, so a refused signature leaves it exactly as it was.
   *
   * <p>A {@code .pom.asc} is verified on every repo, any other artifact {@code .asc} ({@code
   * .jar.asc}, {@code -sources.jar.asc}, ...) only on a repo that verifies every signature
   * (RPS-1188); the signer's key is looked up on the key servers unless the repo switched that off
   * (RPS-1204).
   *
   * <p>On a repo that verifies every signature, a signature whose file is not stored yet, or whose
   * version the POM has not registered yet, is not refused: it is parked ({@link
   * SignatureOutcome#PARKED}) and checked when the file or the POM arrives, which is what Maven's
   * parallel upload needs. On any other repo it is refused with {@code itemNotFound} or {@code
   * artifactVersionNotFound}, as before.
   *
   * @param signedStoragePath the path of the signature, {@code <file>.asc}; the file it signs is
   *     read from storage at the same path without the {@code .asc}
   * @param signature the signature as the client sent it
   * @return {@link SignatureOutcome#VERIFIED} when it verified against the stored file, {@link
   *     SignatureOutcome#PARKED} when it was kept back: the caller stores nothing then
   * @throws io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException when the
   *     signature is malformed or does not match the stored file
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code itemNotFound} when
   *     the signed file is not stored, or when no key server knows the key that made the signature
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code
   *     artifactVersionNotFound} when the version of the signed file is not registered, which its
   *     POM's upload does (RPS-1191)
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code
   *     artifactSigningKeyNotRegistered} when the key is not registered and the repo switched the
   *     key-server lookup off (RPS-1204)
   */
  SignatureOutcome verifySignature(
      BaseRepoInfo<ID> repoInfo, StoragePath signedStoragePath, Resource signature);
}
