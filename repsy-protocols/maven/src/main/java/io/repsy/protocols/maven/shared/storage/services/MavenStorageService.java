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

import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.StorageItemInfo;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.shared.repo.dtos.BaseRepoInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.io.Resource;

@NullMarked
public interface MavenStorageService<ID> {

  void createRepo(UUID repoUuid);

  BaseUsages getUsages(StoragePath storagePath, String repoName, long contentLength)
      throws IOException;

  List<StorageItemInfo> getItems(StoragePath storagePath);

  Resource getResource(String repoName, StoragePath storagePath);

  BaseUsages writeInputStreamToPath(
      StoragePath storagePath, InputStream inputStream, String repoName);

  /**
   * Tells whether a file is stored at {@code storagePath}. A directory at that path counts as
   * stored, so a caller that only removes what it created never removes one (RPS-1199).
   */
  boolean exists(StoragePath storagePath, String repoName);

  /**
   * Soft-deletes the single file at {@code storagePath} (it is moved to the trash, as every other
   * delete is) and nothing around it: its directories are left in place. It only takes back a file
   * the caller has just stored (RPS-1199).
   */
  void deleteFile(StoragePath storagePath);

  long deleteArtifact(UUID repoUuid, String groupId, String artifactId);

  long deleteArtifactVersion(UUID repoUuid, String groupId, String artifactId, String versionName);

  /**
   * Deletes only {@code artifactNames}' own {@code g/a} directories and this group's own
   * group-level metadata files (e.g. a plugin-group {@code maven-metadata.xml}), then prunes this
   * group's directory and any now-empty ancestor. Never deletes a subdirectory that was not passed
   * in {@code artifactNames}, so a nested or sibling group sharing a path prefix (e.g. {@code
   * com.acme.sub} next to {@code com.acme}) is left untouched (RPS-1190).
   */
  long deleteGroup(UUID repoUuid, String groupId, List<String> artifactNames);

  void deleteRepo(UUID repoUuid);

  BaseUsages deleteVersionFromMetadata(
      BaseRepoInfo<ID> repoInfo, String groupId, String artifactId, String versionName)
      throws IOException, XmlPullParserException;

  /**
   * Adds to the artifact-level {@code maven-metadata.xml} that is stored the versions that the
   * repository has registered and the file does not list yet, so a version registered by a client
   * that sends no metadata (Apache Ivy, sbt) is not hidden by the file another client stored
   * earlier (RPS-1437). It only ever adds: a version the file lists and the repository does not
   * know is kept.
   *
   * <p>When a version is added, {@code latest}, {@code release} and {@code lastUpdated} are
   * recomputed, the file and the checksums that are stored next to it are rewritten, and a stored
   * signature of it, which no longer verifies, is deleted. Nothing is written, deleted or created
   * when the artifact has no stored file, when the file has no {@code <versioning>}, or when it
   * lists every registered version already. {@code registeredVersions} is only asked for once the
   * file is known to be there, and it is asked inside the lock that also serializes the delete
   * rewrite and a client's own upload of that file.
   *
   * @return the change of the disk usage of the repository, negative when the files shrank
   * @throws io.repsy.core.error_handling.exceptions.BadRequestException {@code
   *     malformedMetadataFile}, before anything is written, when the stored file cannot be parsed
   */
  long addVersionsToMetadata(
      BaseRepoInfo<ID> repoInfo,
      String groupId,
      String artifactId,
      Supplier<? extends Collection<String>> registeredVersions)
      throws IOException;

  Path getPath(String groupId, String artifactId);

  Resource getResource(StoragePath storagePath, String repoName);

  void clearTrash();
}
