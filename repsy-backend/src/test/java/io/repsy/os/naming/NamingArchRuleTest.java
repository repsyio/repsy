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
package io.repsy.os.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fails the build when a class breaks the naming rules of "Java naming" in AGENTS.md (RPS-2029):
 * the layer packages ({@code controllers}, {@code repositories}, {@code utils}, {@code listeners},
 * {@code services}, {@code configs}) hold only classes with that role suffix,
 * {@code @ConfigurationProperties} classes end in {@code Properties}, and package names are
 * lowercase.
 *
 * <p>It checks the OS backend and every protocol and storage library the backend depends on (the
 * classpath of this module). The {@code Abstract*} base of a library and the same-name backend
 * class both end in the role suffix, so the library/backend pair rule needs no exception.
 *
 * <p>Classes that predate the check are listed below with the reason. The lists may only shrink: a
 * class that is renamed or moved must be deleted from its list (a stale entry fails the test), and
 * a new violation fails the test. Never add an entry; rename the class or put it in the right
 * package. Rules are in {@link NamingRules}; each is proved to fail on a planted violation in
 * {@code io.repsy.os.naming.fixtures}.
 */
class NamingArchRuleTest {

  private static final JavaClasses PRODUCTION =
      new ClassFileImporter()
          .withImportOption(new ImportOption.DoNotIncludeTests())
          .importPackages("io.repsy.os", "io.repsy.protocols", "io.repsy.libs");

  private static final JavaClasses FIXTURES =
      new ClassFileImporter().importPackages("io.repsy.os.naming.fixtures");

  private static final Map<String, String> FROZEN_REPOSITORIES =
      new Frozen()
          .group(
              "Spring Data projection or result type that sits next to its repository; move to dtos or rename",
              "io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestEdgeView",
              "io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestFileView",
              "io.repsy.os.server.protocols.helm.shared.oci.repositories.HelmOciManifestMismatch",
              "io.repsy.os.server.protocols.nuget.shared.packages.repositories.NuGetBuildMetadataVersion")
          .build();

  private static final Map<String, String> FROZEN_UTILS =
      new Frozen()
          .group(
              "stateless helper named after its role (parser, writer, comparator, ...), not *Utils; rename to *Utils or move to a role package",
              "io.repsy.os.server.protocols.docker.protocol.utils.DefaultDockerScopeParser",
              "io.repsy.os.server.protocols.docker.protocol.utils.DockerLayerRenamer",
              "io.repsy.os.server.protocols.docker.shared.utils.ParsedPath",
              "io.repsy.os.server.protocols.helm.protocol.utils.HelmChartMuseumPathParser",
              "io.repsy.os.server.protocols.helm.protocol.utils.HelmOciPathParser",
              "io.repsy.os.server.protocols.helm.protocol.utils.HelmServerPathParser",
              "io.repsy.os.server.security.scan.utils.ScanFailureMessages",
              "io.repsy.os.server.shared.token.utils.DeployTokenHash",
              "io.repsy.os.server.shared.token.utils.TokenUsernameGenerator",
              "io.repsy.os.shared.auth.utils.PasswordHasher",
              "io.repsy.os.shared.auth.utils.TokenRealm",
              "io.repsy.os.shared.error_handling.utils.OciErrors",
              "io.repsy.os.shared.token.utils.TokenFactory",
              "io.repsy.os.shared.token.utils.TokenGenerator",
              "io.repsy.os.shared.token.utils.TokenHash",
              "io.repsy.os.shared.token.utils.TokenScopeConverter",
              "io.repsy.os.shared.utils.MultiPortNames",
              "io.repsy.protocols.docker.shared.utils.AcceptHeaderParser",
              "io.repsy.protocols.docker.shared.utils.BaseParsedPath",
              "io.repsy.protocols.docker.shared.utils.DockerDigestCalculator",
              "io.repsy.protocols.docker.shared.utils.DockerManifestValidator",
              "io.repsy.protocols.docker.shared.utils.DockerPushGuards",
              "io.repsy.protocols.docker.shared.utils.DockerTagPaging",
              "io.repsy.protocols.docker.shared.utils.ManifestNameGenerator",
              "io.repsy.protocols.docker.shared.utils.MediaTypes",
              "io.repsy.protocols.golang.shared.utils.GoModuleHashCalculator",
              "io.repsy.protocols.golang.shared.utils.GoModuleZipReader",
              "io.repsy.protocols.helm.shared.utils.HelmChartParser",
              "io.repsy.protocols.helm.shared.utils.HelmVersionComparator",
              "io.repsy.protocols.maven.shared.utils.ArtifactMetadataSynthesizer",
              "io.repsy.protocols.maven.shared.utils.MavenPublishLimits",
              "io.repsy.protocols.maven.shared.utils.MavenUploadLimits",
              "io.repsy.protocols.maven.shared.utils.PluginDescriptorReader",
              "io.repsy.protocols.npm.shared.utils.ExtractPath",
              "io.repsy.protocols.npm.shared.utils.NpmPackumentBuilder",
              "io.repsy.protocols.npm.shared.utils.NpmPublishLimits",
              "io.repsy.protocols.npm.shared.utils.NpmRevPath",
              "io.repsy.protocols.npm.shared.utils.NpmSemver",
              "io.repsy.protocols.npm.shared.utils.NpmTarballInspector",
              "io.repsy.protocols.npm.shared.utils.NpmVersionComparator",
              "io.repsy.protocols.nuget.shared.utils.NuGetBaseUrlResolver",
              "io.repsy.protocols.nuget.shared.utils.NuGetServiceIndexResources",
              "io.repsy.protocols.nuget.shared.utils.NuGetUrlBuilder",
              "io.repsy.protocols.pypi.shared.utils.PypiPublishLimits",
              "io.repsy.protocols.pypi.shared.utils.PypiVersionComparator",
              "io.repsy.protocols.ruby.shared.utils.CompactIndexFormatter",
              "io.repsy.protocols.ruby.shared.utils.GemFilenameCandidates",
              "io.repsy.protocols.ruby.shared.utils.GemspecParser",
              "io.repsy.protocols.ruby.shared.utils.RubyGemVersionComparator",
              "io.repsy.protocols.ruby.shared.utils.RubyGemspecMarshalWriter",
              "io.repsy.protocols.ruby.shared.utils.RubyMarshalWriter",
              "io.repsy.protocols.ruby.shared.utils.RubySpecsIndexWriter",
              "io.repsy.protocols.shared.utils.BlobDigests",
              "io.repsy.protocols.shared.utils.BoundedEntryReader",
              "io.repsy.protocols.shared.utils.RequestBodies")
          .group(
              "value type, stream wrapper or helper that is not a static *Utils holder; move or rename",
              "io.repsy.protocols.npm.shared.utils.NpmTarballFacts",
              "io.repsy.protocols.pypi.shared.utils.Pep440Version",
              "io.repsy.protocols.pypi.shared.utils.ReleaseVersion",
              "io.repsy.protocols.shared.utils.BoundedLengthInputStream",
              "io.repsy.protocols.shared.utils.SpooledUpload",
              "io.repsy.protocols.shared.utils.StoredUpload")
          .group(
              "settings class in utils; move to configs",
              "io.repsy.protocols.shared.utils.BaseUrlParserProperties")
          .group(
              "exception type in utils; move to an exceptions package",
              "io.repsy.protocols.shared.utils.EntryTooLargeException")
          .build();

  private static final Map<String, String> FROZEN_LISTENERS =
      new Frozen()
          .group(
              "not a listener (issues scan tokens); move out of listeners",
              "io.repsy.os.server.security.shared.listeners.DockerScanTokenIssuer")
          .build();

  private static final Map<String, String> FROZEN_SERVICES =
      new Frozen()
          .group(
              "role-named class in services; rename to *Service or move to its role package",
              "io.repsy.libs.storage.core.services.StorageStrategy",
              "io.repsy.libs.storage.gateway.filesystem.services.FileSystemStorageStrategy",
              "io.repsy.os.server.protocols.docker.shared.auth.services.PatDockerGrants",
              "io.repsy.os.server.protocols.docker.shared.cleanup.services.CleanupPolicyFacade",
              "io.repsy.os.server.protocols.docker.shared.tag.services.ManifestDeleter",
              "io.repsy.os.server.protocols.docker.shared.tag.services.TagDeleter",
              "io.repsy.os.server.protocols.docker.shared.tag.services.UntaggedManifestFinder",
              "io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactUpsertHelper",
              "io.repsy.os.server.protocols.maven.shared.artifact.services.components.ArtifactDeleter",
              "io.repsy.os.server.protocols.maven.shared.keystore.services.MavenPgpCaps",
              "io.repsy.os.server.protocols.npm.shared.audit.services.NpmAdvisoryMapper",
              "io.repsy.os.server.protocols.npm.shared.audit.services.NpmAdvisorySource",
              "io.repsy.os.server.protocols.npm.shared.auth.services.NpmAuthenticatorImpl",
              "io.repsy.os.server.protocols.shared.services.ProtocolApiFacade",
              "io.repsy.os.server.protocols.shared.services.ProtocolApiFacadeMavenAdapter",
              "io.repsy.os.shared.auth.services.LoginInfoFactory",
              "io.repsy.os.shared.repo.services.DefaultRepoSeeder",
              "io.repsy.protocols.cargo.shared.crate.services.SemverComparator",
              "io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer",
              "io.repsy.protocols.golang.shared.module.services.GoModuleFilesWriter",
              "io.repsy.protocols.maven.shared.artifact.services.VersionComparator",
              "io.repsy.protocols.npm.shared.auth.services.NpmIdentityResolver",
              "io.repsy.protocols.npm.shared.auth.services.NpmTokenRevoker")
          .group(
              "protocol authenticator that lives in services; rename or move to an auth package",
              "io.repsy.os.server.protocols.cargo.shared.auth.services.CargoAuthenticator",
              "io.repsy.os.server.protocols.docker.shared.auth.services.DockerAuthenticator",
              "io.repsy.os.server.protocols.golang.shared.auth.services.GoAuthenticator",
              "io.repsy.os.server.protocols.maven.shared.auth.services.MavenAuthenticator",
              "io.repsy.os.server.protocols.nuget.shared.auth.services.NuGetAuthenticator",
              "io.repsy.os.server.protocols.pypi.shared.auth.services.PypiAuthenticator",
              "io.repsy.os.server.protocols.ruby.shared.auth.services.RubyAuthenticator",
              "io.repsy.protocols.npm.shared.auth.services.NpmAuthenticator")
          .group(
              "startup repair runner in services; move to tasks or rename",
              "io.repsy.os.server.protocols.helm.shared.oci.services.HelmOciManifestNameRepairRunner",
              "io.repsy.os.server.protocols.nuget.shared.packages.services.NuGetBuildMetadataVersionMigrationRunner")
          .build();

  private static final Map<String, String> FROZEN_CONFIGS =
      new Frozen()
          .group(
              "filter, interceptor or matcher in configs; move out of configs or rename to *Config",
              "io.repsy.libs.multiport.configs.RepsyConnectorSettings",
              "io.repsy.os.shared.configs.ApiPortMatcher",
              "io.repsy.os.shared.configs.SecurityHeadersFilter")
          .build();

  private static final Map<String, String> FROZEN_CONFIGURATION_PROPERTIES =
      new Frozen()
          .group(
              "@ConfigurationProperties class named *Config; rename to *Properties",
              "io.repsy.libs.storage.core.configs.StorageConfig",
              "io.repsy.libs.storage.gateway.filesystem.configs.FileSystemStorageConfig")
          .build();

  private static final Map<String, String> FROZEN_PACKAGES = Map.of();

  private static Map<String, ArchRule> rules() {
    Map<String, ArchRule> rules = new LinkedHashMap<>();
    rules.put("controllers", NamingRules.CONTROLLERS);
    rules.put("repositories", NamingRules.REPOSITORIES);
    rules.put("utils", NamingRules.UTILS);
    rules.put("listeners", NamingRules.LISTENERS);
    rules.put("services", NamingRules.SERVICES);
    rules.put("configs", NamingRules.CONFIGS);
    rules.put("configurationProperties", NamingRules.CONFIGURATION_PROPERTIES);
    rules.put("packages", NamingRules.PACKAGES);
    return rules;
  }

  private static Map<String, Map<String, String>> frozen() {
    Map<String, Map<String, String>> frozen = new LinkedHashMap<>();
    frozen.put("controllers", Map.of());
    frozen.put("repositories", FROZEN_REPOSITORIES);
    frozen.put("utils", FROZEN_UTILS);
    frozen.put("listeners", FROZEN_LISTENERS);
    frozen.put("services", FROZEN_SERVICES);
    frozen.put("configs", FROZEN_CONFIGS);
    frozen.put("configurationProperties", FROZEN_CONFIGURATION_PROPERTIES);
    frozen.put("packages", FROZEN_PACKAGES);
    return frozen;
  }

  /** Rule name and the one class (or package) in {@code fixtures} that must be reported. */
  private static Stream<Arguments> plantedViolations() {
    return Stream.of(
        Arguments.of("controllers", "io.repsy.os.naming.fixtures.controllers.BadHandler"),
        Arguments.of("repositories", "io.repsy.os.naming.fixtures.repositories.BadStore"),
        Arguments.of("utils", "io.repsy.os.naming.fixtures.utils.BadHelper"),
        Arguments.of("listeners", "io.repsy.os.naming.fixtures.listeners.BadObserver"),
        Arguments.of("services", "io.repsy.os.naming.fixtures.services.BadWorker"),
        Arguments.of("configs", "io.repsy.os.naming.fixtures.configs.BadSettings"),
        Arguments.of(
            "configurationProperties", "io.repsy.os.naming.fixtures.props.BadSettingsConfig"),
        Arguments.of("packages", "io.repsy.os.naming.fixtures.camelCase"));
  }

  private static Set<String> violations(ArchRule rule, JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    rule.evaluate(classes).handleViolations((objects, message) -> found.add(message));
    return found;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ruleNames")
  @DisplayName("no new class breaks a naming rule")
  void noNewViolations(String name) {
    Set<String> unexpected = violations(rules().get(name), PRODUCTION);
    unexpected.removeAll(frozen().get(name).keySet());

    assertTrue(
        unexpected.isEmpty(),
        () ->
            "New naming violations of rule '"
                + name
                + "' (see \"Java naming\" in AGENTS.md; rename the class or move it, do not extend the"
                + " freeze list): "
                + unexpected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ruleNames")
  @DisplayName("the freeze list has no stale entries")
  void freezeListHasNoStaleEntries(String name) {
    Set<String> stale = new TreeSet<>(frozen().get(name).keySet());
    stale.removeAll(violations(rules().get(name), PRODUCTION));

    assertTrue(
        stale.isEmpty(), () -> "Fixed, remove from the '" + name + "' freeze list: " + stale);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("plantedViolations")
  @DisplayName("the rule fails on a planted violation and accepts the good form")
  void ruleFailsOnPlantedViolation(String name, String planted) {
    Set<String> found = violations(rules().get(name), FIXTURES);

    assertEquals(Set.of(planted), found);
  }

  @Test
  @DisplayName("every freeze list entry states a reason")
  void freezeListEntriesStateReason() {
    frozen()
        .forEach(
            (rule, entries) ->
                entries.forEach(
                    (clazz, reason) ->
                        assertFalse(
                            reason.isBlank(), () -> rule + ": " + clazz + " has no reason")));
  }

  static Stream<String> ruleNames() {
    return rules().keySet().stream();
  }

  /** Builds a class to reason map and rejects a class listed twice. */
  private static final class Frozen {
    private final Map<String, String> entries = new LinkedHashMap<>();

    Frozen group(String reason, String... classes) {
      for (String clazz : classes) {
        if (entries.put(clazz, reason) != null) {
          throw new IllegalStateException("Listed twice: " + clazz);
        }
      }
      return this;
    }

    Map<String, String> build() {
      return Map.copyOf(entries);
    }
  }
}
