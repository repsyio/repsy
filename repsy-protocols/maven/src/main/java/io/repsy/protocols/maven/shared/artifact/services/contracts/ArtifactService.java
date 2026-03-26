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

import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactDeployType;
import io.repsy.protocols.maven.shared.artifact.dtos.ArtifactVersionType;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import org.apache.commons.lang3.tuple.MutablePair;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@NullMarked
public interface ArtifactService<ID> {

  @Nullable MutablePair<ArtifactDeployType, ArtifactVersionType> getDeployAndVersionType(
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
}
