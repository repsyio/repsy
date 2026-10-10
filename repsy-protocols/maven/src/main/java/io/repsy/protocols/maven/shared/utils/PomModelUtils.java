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

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.libs.storage.core.dtos.StoragePath;
import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.Resource;

@Slf4j
@UtilityClass
@NullMarked
public class PomModelUtils {

  private static final String MAVEN_PLUGIN = "maven-plugin";

  /**
   * The plugin prefix {@code maven-plugin-plugin} 3.x derives from an artifactId when the plugin
   * sets no {@code goalPrefix}: {@code x-maven-plugin} and {@code maven-x-plugin} give {@code x},
   * and so {@code maven-plugin-plugin} gives {@code plugin}. Any other name is not derivable by
   * that plugin (its build fails and asks for an explicit {@code goalPrefix}), so it falls back to
   * what Maven core used to do: drop every {@code maven} and {@code plugin} word, which gives
   * {@code jooq-codegen} for {@code jooq-codegen-maven}. This is only the fallback for a plugin
   * whose real prefix is not known, {@link PluginDescriptorReader} reads it from the plugin's jar
   * (RPS-1458).
   */
  public static String getPrefixFromArtifactId(final String artifactId) {

    final var suffixed = artifactId.endsWith("-maven-plugin");
    final var prefixed = artifactId.startsWith("maven-") && artifactId.endsWith("-plugin");

    if (suffixed && artifactId.length() > "-maven-plugin".length()) {
      return artifactId.substring(0, artifactId.length() - "-maven-plugin".length());
    } else if (prefixed && artifactId.length() > "maven--plugin".length()) {
      return artifactId.substring("maven-".length(), artifactId.length() - "-plugin".length());
    } else {
      return artifactId.replaceAll("-?maven-?", "").replaceAll("-?plugin-?", "");
    }
  }

  @Nullable
  public static Model readModel(final Resource pomResource) {

    try (final var inputStream = pomResource.getInputStream()) {
      return readModel(inputStream);
    } catch (final IOException e) {
      log.warn("Malformed or unreadable POM file received: {}", e.getMessage());
      throw new BadRequestException(ProtocolErrorCodes.MALFORMED_POM_FILE);
    }
  }

  /**
   * Parses a POM without closing the stream.
   *
   * @throws BadRequestException With the fixed {@code malformedPomFile} id if the POM cannot be
   *     read or parsed
   */
  @Nullable
  public static Model readModel(final InputStream pomStream) {

    final var reader = new MavenXpp3Reader();

    try {
      return reader.read(new InputStreamReader(pomStream, UTF_8));
    } catch (final IOException | XmlPullParserException e) {
      log.warn("Malformed or unreadable POM file received: {}", e.getMessage());
      throw new BadRequestException(ProtocolErrorCodes.MALFORMED_POM_FILE);
    }
  }

  /** The groupId a POM declares: its own, else its parent's (Maven inherits it), else null. */
  public static @Nullable String declaredGroupId(final Model model) {

    if (model.getGroupId() != null) {
      return model.getGroupId();
    }

    return model.getParent() != null ? model.getParent().getGroupId() : null;
  }

  /**
   * Refuses a POM whose declared groupId is not the group of its path. The artifact service
   * registers a version under the group of the path, so a POM of another group used to be stored
   * and answered 200 but never registered: served, yet invisible and undeletable in the panel and
   * out of reach of the version events and the scanner (RPS-1193).
   *
   * <p>There is no check when the POM declares no groupId at all (Maven refuses such a POM itself)
   * or when the path has no GAV (it is refused earlier as {@code invalidArtifactPath}). The
   * comparison is case-sensitive like the repository layout, and the artifactId and the version are
   * not compared: they are never used for the registration, and {@code ${revision}}, an inherited
   * version or an sbt cross-versioned artifactId would be refused wrongly.
   *
   * @param model The parsed POM, {@code null} when there is none to check
   * @param path The repository-relative path the POM is uploaded to
   * @throws BadRequestException With the fixed {@code pomGroupIdMismatch} id
   */
  public static void checkPomGroupIdMatchesPath(final @Nullable Model model, final String path) {

    final var gav = MavenGavUtils.convertPathToGav(path);

    if (model == null || gav == null) {
      return;
    }

    final var declared = declaredGroupId(model);

    if (declared != null && !declared.equals(gav.getGroupId())) {
      log.info(
          "Refusing POM {}: it declares groupId {} under group {}",
          path,
          declared,
          gav.getGroupId());
      throw new BadRequestException(ProtocolErrorCodes.POM_GROUP_ID_MISMATCH);
    }
  }

  public static boolean isPomToParse(final StoragePath storagePath) {
    return MavenFileNameUtils.isPomFile(storagePath.getRelativePath().getFileName());
  }

  public static boolean artifactIsPlugin(final Model model) {

    return MAVEN_PLUGIN.equalsIgnoreCase(model.getPackaging());
  }
}
