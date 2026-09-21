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
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import org.apache.commons.lang3.tuple.MutablePair;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface ArtifactService<ID> {

  /**
   * Classifies an upload of a file that is not a {@code maven-metadata.xml}.
   *
   * @throws BadRequestException {@code invalidArtifactPath} when the path is not a Maven 2 artifact
   *     path (checksums of any path and metadata are not judged here)
   */
  MutablePair<ArtifactDeployType, ArtifactVersionType> getDeployAndVersionType(
      BaseRepoInfo<ID> repoInfo, StoragePath storagePath);

  MutablePair<ArtifactDeployType, ArtifactVersionType> getDeployAndVersionTypesByMetadataTypeFiles(
      BaseRepoInfo<ID> repoInfo, byte[] content, String fileName)
      throws IOException, XmlPullParserException;

  void checkDeploymentRules(
      BaseRepoInfo<ID> repoInfo,
      MutablePair<ArtifactDeployType, ArtifactVersionType> artifactPair,
      StoragePath storagePath);

  void createOrUpdateArtifact(
      BaseRepoInfo<ID> repoInfo, StoragePath storagePath, Resource resource);

  StoragePath getNonSignedStoragePath(StoragePath storagePath);

  /**
   * Verifies a detached signature of a file that is already stored. Nothing is written or deleted,
   * so a refused signature leaves the repository exactly as it was.
   *
   * @param signedStoragePath the path of the signature, {@code <file>.asc}; the file it signs is
   *     read from storage at the same path without the {@code .asc}
   * @param signature the signature as the client sent it
   * @throws io.repsy.core.error_handling.exceptions.SignatureNotVerifiedException when the
   *     signature is malformed or does not match the stored file
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code itemNotFound} when
   *     the signed file is not stored, or when no key server knows the key that made the signature
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code
   *     artifactVersionNotFound} when the POM is stored but its version is not registered
   *     (RPS-1191)
   */
  void verifySignature(
      BaseRepoInfo<ID> repoInfo, StoragePath signedStoragePath, Resource signature);
}
