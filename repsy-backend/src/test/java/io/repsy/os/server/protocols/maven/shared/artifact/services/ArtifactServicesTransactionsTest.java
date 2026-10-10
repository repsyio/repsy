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
package io.repsy.os.server.protocols.maven.shared.artifact.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the transaction attribute of every public operation of the Maven artifact services (RPS-2064
 * split of {@code ArtifactService}): the same table holds before and after the split, so a method
 * that moved to another class keeps running read only, or read write, exactly as it did.
 *
 * <p>The table is keyed by method name: every public method of that name, in whichever of the
 * classes below declares it (a delegating overload included), must have the attribute. Helpers
 * added by the split that are not in the table are not constrained.
 */
@DisplayName("Maven artifact services keep their transaction attributes (RPS-2064)")
class ArtifactServicesTransactionsTest {

  private static final String READ_ONLY = "readOnly";
  private static final String READ_WRITE = "readWrite";

  private static final List<Class<?>> SERVICES =
      List.of(
          ArtifactDeploymentService.class,
          ArtifactQueryService.class,
          ArtifactSignatureService.class,
          MavenPluginMetadataService.class);

  private static final Map<String, String> EXPECTED =
      Map.ofEntries(
          // The upload path and the deletes write.
          Map.entry("createOrUpdateArtifact", READ_WRITE),
          Map.entry("refreshPluginPrefixFromJar", READ_WRITE),
          Map.entry("deleteArtifact", READ_WRITE),
          Map.entry("deleteArtifactVersion", READ_WRITE),
          Map.entry("deleteGroup", READ_WRITE),
          // Reads and judgements: verifySignature runs read only, it parks in a transaction of
          // its own.
          Map.entry("getVersionType", READ_ONLY),
          Map.entry("getVersionTypeByMetadataTypeFiles", READ_ONLY),
          Map.entry("checkDeploymentRules", READ_ONLY),
          Map.entry("getRegisteredVersions", READ_ONLY),
          Map.entry("getRegisteredPlugins", READ_ONLY),
          Map.entry("getNonSignedStoragePath", READ_ONLY),
          Map.entry("verifySignature", READ_ONLY),
          Map.entry("requireArtifactVersion", READ_ONLY),
          Map.entry("requireArtifact", READ_ONLY),
          Map.entry("requireGroup", READ_ONLY),
          Map.entry("getGroupSummary", READ_ONLY),
          Map.entry("hasOnlyOneArtifact", READ_ONLY),
          Map.entry("hasOnlyOneVersion", READ_ONLY),
          Map.entry("getArtifactVersion", READ_ONLY),
          Map.entry("getArtifactVersionPomFilename", READ_ONLY),
          Map.entry("getArtifactVersions", READ_ONLY),
          Map.entry("getArtifactVersionsContainsVersion", READ_ONLY),
          Map.entry("getArtifactsContainsGroupName", READ_ONLY),
          Map.entry("getArtifactsContainsArtifactName", READ_ONLY),
          Map.entry("getArtifacts", READ_ONLY),
          Map.entry("getArtifactVersionNames", READ_ONLY),
          // The version signature state joins the transaction of the upload it is called from.
          Map.entry("recordVerified", READ_WRITE),
          Map.entry("lock", READ_WRITE),
          Map.entry("lockAndIsVerifyAll", READ_WRITE),
          Map.entry("forget", READ_WRITE),
          Map.entry("refreshSigned", READ_WRITE),
          Map.entry("recompute", READ_WRITE),
          Map.entry("isRecorded", READ_ONLY),
          Map.entry("findRecordedId", READ_ONLY));

  @Test
  @DisplayName("every public operation has the transaction attribute it had before the split")
  void everyOperationKeepsItsAttribute() {
    final var actual = new ArrayList<String>();
    final var seen = new ArrayList<String>();

    for (final var service : SERVICES) {
      for (final Method method : service.getDeclaredMethods()) {
        final var expected = EXPECTED.get(method.getName());

        if (expected == null
            || !Modifier.isPublic(method.getModifiers())
            || Modifier.isStatic(method.getModifiers())) {
          continue;
        }

        seen.add(method.getName());

        final var mode = modeOf(service, method);

        if (!expected.equals(mode)) {
          actual.add(service.getSimpleName() + "#" + method.getName() + " is " + mode);
        }
      }
    }

    assertThat(actual).as("operations whose transaction attribute changed").isEmpty();
    assertThat(seen).as("operations of the table that were found").containsAll(EXPECTED.keySet());
  }

  private static String modeOf(final Class<?> service, final Method method) {

    final var onMethod = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
    final var attribute =
        onMethod != null
            ? onMethod
            : AnnotatedElementUtils.findMergedAnnotation(service, Transactional.class);

    if (attribute == null) {
      return "none";
    }

    return attribute.readOnly() ? READ_ONLY : READ_WRITE;
  }
}
