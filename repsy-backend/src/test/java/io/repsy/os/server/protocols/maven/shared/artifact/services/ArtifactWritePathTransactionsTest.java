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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pins the propagation of the Maven write path (RPS-2167), which {@code
 * ArtifactServicesTransactionsTest} does not see because it only lists public operations by their
 * read only or read write mode: the two row inserts run in a transaction of their own so a unique
 * violation of a concurrent upload rolls back only that insert, and every other write joins the
 * transaction of the upload. The split of the write path into several services must not change
 * either.
 */
@DisplayName("Maven write path keeps its transaction propagation (RPS-2167)")
class ArtifactWritePathTransactionsTest {

  private static Transactional attribute(final Class<?> type, final String name) {
    for (final Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
      }
    }

    throw new AssertionError(type.getSimpleName() + "#" + name + " does not exist");
  }

  @Test
  @DisplayName("the artifact and the version row are inserted in a transaction of their own")
  void theRowInsertsRequireANewTransaction() {
    for (final var name : List.of("insertArtifact", "insertArtifactVersion")) {
      final var attribute = attribute(ArtifactUpsertHelper.class, name);

      assertThat(attribute).as(name).isNotNull();
      assertThat(attribute.propagation()).as(name).isEqualTo(Propagation.REQUIRES_NEW);
      assertThat(attribute.readOnly()).as(name).isFalse();
    }
  }

  @Test
  @DisplayName("the dependent version writes join the transaction of the caller")
  void theDependentWritesJoinTheCallersTransaction() {
    for (final var name :
        List.of(
            "createVersionDevelopers", "createVersionLicenses", "updateReleaseAndLatestVersion")) {
      final var attribute = attribute(ArtifactVersionWriteService.class, name);

      assertThat(attribute).as(name).isNotNull();
      assertThat(attribute.propagation()).as(name).isEqualTo(Propagation.REQUIRED);
      assertThat(attribute.readOnly()).as(name).isFalse();
    }
  }

  @Test
  @DisplayName("the uploads and deletes join an outer transaction, and the judgements read only")
  void theEntryPointsKeepTheirPropagation() {
    final var writes =
        List.of("createOrUpdateArtifact", "deleteArtifact", "deleteArtifactVersion", "deleteGroup");
    final var wrong = new ArrayList<String>();

    for (final var name : writes) {
      final var attribute = attribute(ArtifactDeploymentService.class, name);

      if (attribute == null
          || attribute.propagation() != Propagation.REQUIRED
          || attribute.readOnly()) {
        wrong.add(name);
      }
    }

    final var classLevel =
        AnnotatedElementUtils.findMergedAnnotation(
            ArtifactDeploymentService.class, Transactional.class);
    final var byPropagation =
        Map.of(
            "class",
            classLevel == null ? "none" : classLevel.propagation() + "/" + classLevel.readOnly());

    assertThat(wrong).as("write entry points that are not plain @Transactional").isEmpty();
    assertThat(byPropagation).containsEntry("class", "REQUIRED/true");
  }
}
