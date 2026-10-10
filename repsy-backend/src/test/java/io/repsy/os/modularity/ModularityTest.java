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

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import io.repsy.os.RepsyApplication;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Fails the build when the backend breaks its module structure (RPS-2055). Needs no Docker.
 *
 * <p>{@link #modulesAreWellFormed()} runs Spring Modulith's {@code verify()}: no cycle between
 * modules (the packages carrying {@code @ApplicationModule}), no use of another module's internals,
 * and no use of a module that is not allowed by its {@code allowedDependencies}. It passes with no
 * exceptions, so a new violation fails the build.
 *
 * <p>{@link #packageCyclesDoNotGrow()} checks the packages below the modules, which Modulith does
 * not see. The cycles that predate the check are listed in {@link #FROZEN_CYCLES} with the reason.
 * The list may only shrink: a cycle that is fixed must be deleted from it (a stale entry fails the
 * test), and a new cycle fails the test. Never add an entry; break the cycle with an event, a port
 * interface or by moving the class to the package that owns it.
 *
 * <p>The generated OpenAPI model ({@code io.repsy.os.generated}) is excluded: it is build output,
 * and its types are meant to be used from every package.
 */
@DisplayName("Module structure")
class ModularityTest {

  private static final String GENERATED = "io.repsy.os.generated";

  /** The slices whose package cycles are checked, one rule per pattern. */
  private static final List<String> SLICE_PATTERNS =
      List.of(
          "io.repsy.os.(*)..",
          "io.repsy.os.server.(*)..",
          "io.repsy.os.server.security.(*)..",
          "io.repsy.os.server.protocols.(*).(*)..",
          "io.repsy.os.server.protocols.(*).shared.(*)..",
          "io.repsy.os.shared.(*)..",
          "io.repsy.os.panel.(*)..");

  private static final Map<String, String> FROZEN_CYCLES = new LinkedHashMap<>();

  static {
    FROZEN_CYCLES.put(
        "io.repsy.os.server.(*).. :: protocols -> security",
        "NpmAdvisorySource and NuGetBuildMetadataVersionMigrationService read the scan services and repository, and the security module reads the Ruby repositories; give the scan data a port interface that the protocols implement (RPS-2055 follow-up)");
    FROZEN_CYCLES.put(
        "io.repsy.os.server.protocols.(*).shared.(*).. :: docker - image -> docker - layer -> docker - tag",
        "the Image, Layer and Manifest entities and their services reference each other; merge image, layer and tag into one aggregate package (RPS-2055 follow-up: moves 36 classes and changes the imports of about 80 files, so it is its own change)");
    FROZEN_CYCLES.put(
        "io.repsy.os.server.protocols.(*).shared.(*).. :: docker - image -> docker - tag",
        "same aggregate as the three-slice cycle above: image and tag use each other's entities and repositories");
    FROZEN_CYCLES.put(
        "io.repsy.os.server.protocols.(*).shared.(*).. :: docker - layer -> docker - tag",
        "same aggregate as the three-slice cycle above: Layer.manifests points at Manifest and the tag services use layers");
    FROZEN_CYCLES.put(
        "io.repsy.os.server.protocols.(*).shared.(*).. :: maven - artifact -> maven - keystore",
        "ArtifactMapper maps both artifacts and keystore items, and KeyStoreService uses it; split the keystore mappings into their own mapper");
    FROZEN_CYCLES.put(
        "io.repsy.os.server.security.(*).. :: scan -> shared",
        "VulnerabilityScanTxService and VulnerabilityScanController use the resolver registry and trigger service of security.shared, which use the scan services back; move the registry and trigger service into scan or put an interface between them");
    FROZEN_CYCLES.put(
        "io.repsy.os.shared.(*).. :: auth -> token -> user",
        "PanelAuthHelper uses the personal access token services, which use the user services, which use the auth utils; move the token-type and scope helpers out of auth");
    FROZEN_CYCLES.put(
        "io.repsy.os.shared.(*).. :: auth -> user",
        "user services use AuthUtils and PanelAuthHelper from auth, and auth reads the user entity; same fix as the three-slice auth cycle");
  }

  @Test
  @DisplayName("Spring Modulith verify() finds no violation")
  void modulesAreWellFormed() {

    ApplicationModules.of(
            RepsyApplication.class,
            DescribedPredicate.describe(
                "generated OpenAPI model", c -> c.getPackageName().startsWith(GENERATED)))
        .verify();
  }

  @Test
  @DisplayName("Package cycles are only the frozen ones")
  void packageCyclesDoNotGrow() {

    final var found = new TreeSet<String>();

    for (final var pattern : SLICE_PATTERNS) {
      found.addAll(cyclesOf(pattern));
    }

    final var added = new TreeSet<>(found);
    added.removeAll(FROZEN_CYCLES.keySet());

    final var stale = new TreeSet<>(FROZEN_CYCLES.keySet());
    stale.removeAll(found);

    assertThat(added).as("new package cycles: break them, do not freeze them").isEmpty();
    assertThat(stale)
        .as("cycles that are gone: delete them from FROZEN_CYCLES so they cannot come back")
        .isEmpty();
  }

  @Test
  @DisplayName("Every frozen cycle states its reason")
  void frozenCyclesHaveReasons() {

    assertThat(FROZEN_CYCLES.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
  }

  private static final Pattern SLICE_LINE = Pattern.compile("^\\s*Slice (.+?)(?: ->)?\\s*$");

  private static Set<String> cyclesOf(final String pattern) {

    final JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.repsy.os")
            .that(
                DescribedPredicate.describe(
                    "not generated", (JavaClass c) -> !c.getPackageName().startsWith(GENERATED)));

    final var cycles = new LinkedHashSet<String>();

    try {
      SlicesRuleDefinition.slices().matching(pattern).should().beFreeOfCycles().check(classes);
    } catch (final AssertionError e) {
      List<String> current = null;

      for (final var line : String.valueOf(e.getMessage()).split("\n")) {
        if (line.contains("Cycle detected: ")) {
          flush(pattern, current, cycles);
          current = new ArrayList<>();
          current.add(sliceName(line.substring(line.indexOf("Cycle detected: ") + 16)));
        } else if (current != null
            && SLICE_LINE.matcher(line).matches()
            && !line.contains("Dependencies of")) {
          current.add(sliceName(line));
        } else {
          flush(pattern, current, cycles);
          current = null;
        }
      }

      flush(pattern, current, cycles);
    }

    return cycles;
  }

  private static String sliceName(final String line) {

    final var matcher =
        SLICE_LINE.matcher(line.trim().startsWith("Slice") ? line : "Slice " + line);

    return matcher.matches() ? matcher.group(1) : line.trim();
  }

  /** Adds the cycle once, rotated so that it starts at its smallest slice. */
  private static void flush(
      final String pattern, final List<String> slices, final Set<String> cycles) {

    if (slices == null || slices.size() < 3) {
      return;
    }

    final var ring = new ArrayList<>(slices.subList(0, slices.size() - 1));
    final var start = ring.indexOf(Collections.min(ring));
    Collections.rotate(ring, -start);

    cycles.add(pattern + " :: " + String.join(" -> ", ring));
  }
}
