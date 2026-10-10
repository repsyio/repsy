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
package io.repsy.os.modularity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Layering rules of the OS backend (RPS-2055): who may use a repository, and which protocol may use
 * which. Needs no Docker.
 *
 * <ul>
 *   <li>A class in a {@code controllers} or {@code facades} package does not use a Spring Data
 *       repository: it goes through a service, which owns the transaction and the mapping.
 *   <li>A concrete protocol ({@code protocols.docker}, {@code protocols.maven}, ...) does not use
 *       another concrete protocol, and {@code protocols.shared} does not use a concrete protocol.
 *       What protocols have in common sits in {@code protocols.shared} or behind an interface that
 *       the protocols implement.
 * </ul>
 *
 * <p>The violations that predate the check are listed with the reason. The lists may only shrink: a
 * violation that is fixed must be deleted from its list (a stale entry fails the test), and a new
 * one fails the test. Never add an entry; use a service, an event or a port interface.
 */
@DisplayName("Layering")
class LayeringArchRuleTest {

  private static final String PROTOCOLS = "io.repsy.os.server.protocols.";

  private static final Pattern PROTOCOL_CLASS =
      Pattern.compile("^io\\.repsy\\.os\\.server\\.protocols\\.([a-z]+)\\..*");

  private static final Set<String> CONCRETE_PROTOCOLS =
      Set.of("cargo", "docker", "golang", "helm", "maven", "npm", "nuget", "pypi", "ruby");

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("io.repsy.os");

  /** Edges "origin -> target" of controllers and facades that use a repository. */
  private static final Map<String, String> FROZEN_WEB_TO_REPOSITORY = new LinkedHashMap<>();

  static {
    final var go = "io.repsy.os.server.protocols.golang.shared.go_module.repositories.";
    final var facade = "io.repsy.os.server.protocols.golang.ui.facades.GoApiFacade -> ";
    final var goReason =
        "GoApiFacade deletes modules and versions with the repositories; move it into GoModuleService";
    FROZEN_WEB_TO_REPOSITORY.put(facade + go + "GoModuleRepository", goReason);
    FROZEN_WEB_TO_REPOSITORY.put(facade + go + "GoModuleVersionRepository", goReason);
  }

  /** Edges "origin -> target" between protocols, or from the shared protocol code to one. */
  private static final Map<String, String> FROZEN_PROTOCOL_EDGES = new LinkedHashMap<>();

  static {
    final var shared = "io.repsy.os.server.protocols.shared.";
    final var abandoned = shared + "services.AbandonedBlobUploadCleanupService -> ";
    final var reason =
        "the cleanup service names the Docker and Helm repositories and storage; replace them with an AbandonedBlobUploadSource SPI";
    for (final var target :
        new String[] {
          "docker.shared.layer.repositories.LayerRepository",
          "docker.shared.storage.services.DockerStorageService",
          "helm.shared.chart.entities.HelmChartVersion",
          "helm.shared.chart.repositories.HelmChartVersionRepository",
          "helm.shared.oci.repositories.HelmOciBlobRepository",
          "helm.shared.oci.repositories.HelmOciManifestRepository",
          "helm.shared.storage.services.HelmStorageService"
        }) {
      FROZEN_PROTOCOL_EDGES.put(abandoned + "io.repsy.os.server.protocols." + target, reason);
    }
    FROZEN_PROTOCOL_EDGES.put(
        shared
            + "configs.DefaultRepoDefinitionsConfig -> io.repsy.os.server.protocols.docker.shared.storage.services.DockerStorageService",
        "the Docker default repo bean sits with the other eight; move it into the docker package");
  }

  @Test
  @DisplayName("Controllers and facades do not use repositories")
  void webLayerDoesNotUseRepositories() {

    final var found = new TreeSet<String>();

    for (final var origin : PRODUCTION) {
      if (!inWebLayer(origin)) {
        continue;
      }

      for (final var dependency : origin.getDirectDependenciesFromSelf()) {
        final var target = dependency.getTargetClass();

        if (isRepository(target) && !target.getName().equals(origin.getName())) {
          found.add(origin.getName() + " -> " + target.getName());
        }
      }
    }

    assertFrozen(found, FROZEN_WEB_TO_REPOSITORY, "controller/facade -> repository");
  }

  @Test
  @DisplayName("A protocol does not depend on another protocol")
  void protocolsAreIndependent() {

    final var found = new TreeSet<String>();

    for (final var origin : PRODUCTION) {
      final var from = protocolOf(origin.getName());

      if (from == null) {
        continue;
      }

      for (final var dependency : origin.getDirectDependenciesFromSelf()) {
        final var to = protocolOf(dependency.getTargetClass().getName());

        if (to != null
            && !to.equals(from)
            && CONCRETE_PROTOCOLS.contains(to)
            && (CONCRETE_PROTOCOLS.contains(from) || "shared".equals(from))) {
          found.add(origin.getName() + " -> " + dependency.getTargetClass().getName());
        }
      }
    }

    assertFrozen(found, FROZEN_PROTOCOL_EDGES, "protocol -> other protocol");
  }

  @Test
  @DisplayName("Every frozen entry states its reason")
  void frozenEntriesHaveReasons() {

    assertThat(FROZEN_WEB_TO_REPOSITORY.values()).allSatisfy(r -> assertThat(r).isNotBlank());
    assertThat(FROZEN_PROTOCOL_EDGES.values()).allSatisfy(r -> assertThat(r).isNotBlank());
  }

  private static boolean inWebLayer(final JavaClass type) {

    final var pkg = type.getPackageName();

    return pkg.endsWith(".controllers")
        || pkg.contains(".controllers.")
        || pkg.endsWith(".facades")
        || pkg.contains(".facades.");
  }

  private static boolean isRepository(final JavaClass type) {

    return type.getPackageName().endsWith(".repositories")
        && type.getName().startsWith("io.repsy.os.");
  }

  private static String protocolOf(final String className) {

    if (!className.startsWith(PROTOCOLS)) {
      return null;
    }

    final var matcher = PROTOCOL_CLASS.matcher(className);

    return matcher.matches() ? matcher.group(1) : null;
  }

  private static void assertFrozen(
      final Set<String> found, final Map<String, String> frozen, final String what) {

    final var added = new TreeSet<>(found);
    added.removeAll(frozen.keySet());

    final var stale = new TreeSet<>(frozen.keySet());
    stale.removeAll(found);

    assertThat(added)
        .as("new " + what + " edges: use a service or a port, do not freeze")
        .isEmpty();
    assertThat(stale)
        .as("edges that are gone: delete them from the frozen list so they cannot come back")
        .isEmpty();
  }
}
