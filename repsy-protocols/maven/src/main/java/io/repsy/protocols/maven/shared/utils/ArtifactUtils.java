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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.repsy.core.error_handling.exceptions.ErrorOccurredException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.maven.shared.artifact.services.VersionComparator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.index.artifact.Gav;
import org.apache.maven.index.artifact.M2GavCalculator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@UtilityClass
@NullMarked
public class ArtifactUtils {

  private static final String METADATA_FILENAME = "maven-metadata.xml";
  private static final String MAVEN_PLUGIN = "maven-plugin";
  private static final Set<String> CHECKSUM_TYPES = Set.of(".md5", ".sha1", ".sha256", ".sha512");

  public static boolean containsIgnoreCase(final String str, final String subString) {

    return str.toLowerCase(Locale.getDefault())
        .contains(subString.toLowerCase(Locale.getDefault()));
  }

  @Nullable
  private static Gav convertPathToBasicGav(final String str) {

    final var s = str.startsWith("/") ? str.substring(1) : str;

    final var vEndPos = s.lastIndexOf('/');

    if (vEndPos == -1) {
      return null;
    }

    final var aEndPos = s.lastIndexOf('/', vEndPos - 1);

    if (aEndPos == -1) {
      return null;
    }

    final var gEndPos = s.lastIndexOf('/', aEndPos - 1);

    if (gEndPos == -1) {
      return null;
    }

    final var groupId = s.substring(0, gEndPos).replace('/', '.');
    final var artifactId = s.substring(gEndPos + 1, aEndPos);
    final var version = s.substring(aEndPos + 1, vEndPos);

    return new Gav(groupId, artifactId, version);
  }

  @Nullable
  public static Gav convertPathToGav(final String path) {

    return new M2GavCalculator().pathToGav(path);
  }

  public static @Nullable Gav getGavByFile(final StoragePath storagePath) {

    if (ArtifactUtils.containsIgnoreCase(
        storagePath.getRelativePath().getPath(), METADATA_FILENAME)) {
      return ArtifactUtils.convertPathToBasicGav(storagePath.getRelativePath().getPath());
    } else {
      return ArtifactUtils.convertPathToGav(storagePath.getRelativePath().getPath());
    }
  }

  public static String getPrefixFromArtifactId(final String artifactId) {

    if ("maven-plugin-plugin".equals(artifactId)) {
      return "plugin";
    } else {
      return artifactId.replaceAll("-?maven-?", "").replaceAll("-?plugin-?", "");
    }
  }

  public static boolean isSnapshot(final String versionName) {

    return org.apache.maven.artifact.ArtifactUtils.isSnapshot(versionName);
  }

  @Nullable
  public static Metadata readMetadata(final byte[] content)
      throws IOException, XmlPullParserException {

    final var reader = new MetadataXpp3Reader();

    return reader.read(new ByteArrayInputStream(content), false);
  }

  @SneakyThrows
  @Nullable
  public static Model readModel(final Resource pomResource) {

    final var reader = new MavenXpp3Reader();

    try (final var streamReader = new InputStreamReader(pomResource.getInputStream(), UTF_8)) {
      return reader.read(streamReader);
    } catch (final IOException | XmlPullParserException e) {

      String pomContent = "Failed to read POM content: ";

      try (final InputStream is = pomResource.getInputStream()) {
        pomContent = "\n========POM CONTENT========\n";
        pomContent += new String(is.readAllBytes(), UTF_8);
        pomContent += "===========================";
      } catch (final IOException ioException) {

        pomContent += ioException.getMessage();
      }

      throw new ErrorOccurredException(pomContent, e);
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
      if (!ArtifactUtils.isSnapshot(versioning.getVersions().get(i))) {
        release = versioning.getVersions().get(i);
        break;
      }
    }

    versioning.setLatest(latest);
    versioning.setRelease(release);
  }

  public static boolean artifactIsPlugin(final Model model) {

    return MAVEN_PLUGIN.equalsIgnoreCase(model.getPackaging());
  }

  private static void sortVersions(final Metadata metadata) {

    if (metadata.getVersioning() == null) {
      return;
    }

    if (metadata.getVersioning().getVersions() != null) {
      metadata.getVersioning().getVersions().sort(new VersionComparator());
    }
  }

  public static boolean isPluginMetadata(final @Nullable Metadata metadata) {

    return metadata != null && metadata.getPlugins() != null;
  }

  public static boolean isChecksumFile(final String fileName) {

    final var dotIndex = fileName.lastIndexOf('.');

    return CHECKSUM_TYPES.contains(
        ((dotIndex == -1) ? "" : fileName.substring(dotIndex)).toLowerCase(Locale.getDefault()));
  }

  public static boolean isFileSuitableForGavExtraction(final String fileName) {

    // Condition for detection metadata files and metadata hash files.
    if (ArtifactUtils.containsIgnoreCase(fileName, METADATA_FILENAME)) {
      return isSnapshot(fileName);
    }

    return true;
  }
}
