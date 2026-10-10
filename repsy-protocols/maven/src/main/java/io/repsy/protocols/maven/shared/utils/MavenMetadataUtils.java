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
package io.repsy.protocols.maven.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;

@Slf4j
@UtilityClass
@NullMarked
public class MavenMetadataUtils {

  /**
   * Parses a {@code maven-metadata.xml}. Never returns {@code null}: content that cannot be parsed
   * is refused with the unchecked {@link BadRequestException} {@code malformedMetadataFile}, so a
   * caller that wants a fallback instead has to catch that exception (RPS-1180).
   */
  public static Metadata readMetadata(final byte[] content) {

    final var reader = new MetadataXpp3Reader();

    try {
      return reader.read(new ByteArrayInputStream(content), false);
    } catch (final IOException | XmlPullParserException e) {
      log.warn("Malformed or incomplete maven-metadata.xml received ({})", e.getClass().getName());
      throw new BadRequestException(ProtocolErrorCodes.MALFORMED_METADATA_FILE);
    }
  }

  public static void setReleaseAndLatest(final Metadata metadata) {

    // sorts the versions and finds real release and latest versions not to put last
    // updated version.
    sortVersions(metadata);

    final var versioning = metadata.getVersioning();

    if (versioning == null || metadata.getVersioning().getVersions().isEmpty()) {
      return;
    }

    final var latest = versioning.getVersions().getLast();

    String release = null;

    for (int i = versioning.getVersions().size() - 1; i >= 0; i--) {
      if (!SnapshotNameUtils.isSnapshot(versioning.getVersions().get(i))) {
        release = versioning.getVersions().get(i);
        break;
      }
    }

    versioning.setLatest(latest);
    versioning.setRelease(release);
  }

  private static void sortVersions(final Metadata metadata) {

    if (metadata.getVersioning() == null) {
      return;
    }

    if (metadata.getVersioning().getVersions() != null) {
      metadata.getVersioning().getVersions().sort(new VersionComparator());
    }
  }

  /**
   * Tells the group-level {@code maven-metadata.xml} Maven writes for plugin deploys. {@code
   * Metadata.getPlugins()} never returns {@code null}: it creates an empty list on first access, so
   * the presence of at least one plugin is what makes the file plugin metadata.
   */
  public static boolean isPluginMetadata(final Metadata metadata) {

    return !metadata.getPlugins().isEmpty();
  }

  /**
   * Tells the version-level {@code maven-metadata.xml}, the {@code g/a/<baseVersion>/} file Maven
   * writes for snapshot deploys. It is the only metadata file that names a single version, in its
   * {@code <version>} element; the artifact-level file lists every version and the group-level file
   * lists plugins, and neither has one.
   */
  public static boolean isVersionLevelMetadata(final Metadata metadata) {

    return metadata.getVersion() != null && !metadata.getVersion().isBlank();
  }
}
