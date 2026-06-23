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
package io.repsy.os.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(
    packages = "io.repsy.os.server.protocols",
    importOptions = ImportOption.DoNotIncludeTests.class)
class LayeringArchitectureTest {

  /**
   * Repository classes must not carry a class-level @Transactional.
   * Global: verified clean across all existing protocol packages.
   */
  @ArchTest
  static final ArchRule repositoriesMustNotBeTransactional =
      noClasses()
          .that()
          .resideInAPackage("..repositories..")
          .should()
          .beAnnotatedWith(Transactional.class);

  /**
   * Ruby service implementation classes must be annotated @Service and @Transactional.
   * Scoped to ruby: storage/auth @Service classes in docker, maven, npm, etc. intentionally
   * omit @Transactional because they perform I/O, not DB operations.
   * allowEmptyShould: passes before any ruby service classes exist.
   * Note: readOnly=true is not verified by this rule — review manually with code-reviewer.
   */
  @ArchTest
  static final ArchRule rubyServicesMustBeAnnotatedWithTransactional =
      classes()
          .that()
          .resideInAPackage("..ruby..services..")
          .and()
          .areAnnotatedWith(Service.class)
          .should()
          .beAnnotatedWith(Transactional.class)
          .allowEmptyShould(true);

  /**
   * Ruby service methods must not return Optional.
   * Scoped to ruby: NpmPackageServiceImpl and others have pre-existing Optional returns.
   * allowEmptyShould: passes before any ruby service classes exist.
   */
  @ArchTest
  static final ArchRule rubyServicesMustNotReturnOptional =
      noMethods()
          .that()
          .arePublic()
          .and()
          .areDeclaredInClassesThat()
          .resideInAPackage("..ruby..services..")
          .should()
          .haveRawReturnType(Optional.class)
          .allowEmptyShould(true);

  /**
   * Ruby service public methods must not return @Entity-annotated types.
   * Scoped to ruby: ManifestTxService in docker has pre-existing entity leakage.
   * allowEmptyShould: passes before any ruby service classes exist.
   */
  @ArchTest
  static final ArchRule rubyServicesMustNotLeakEntities =
      classes()
          .that()
          .resideInAPackage("..ruby..services..")
          .and()
          .areAnnotatedWith(Service.class)
          .should(
              new ArchCondition<JavaClass>("not have public methods returning @Entity types") {
                @Override
                public void check(final JavaClass clazz, final ConditionEvents events) {
                  for (final JavaMethod method : clazz.getMethods()) {
                    if (!method
                        .getModifiers()
                        .contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                      continue;
                    }
                    final JavaClass returnType = method.getRawReturnType();
                    if (returnType.isAnnotatedWith(Entity.class)) {
                      events.add(
                          SimpleConditionEvent.violated(
                              clazz,
                              method.getFullName()
                                  + " leaks entity type: "
                                  + returnType.getName()));
                    }
                  }
                }
              })
          .allowEmptyShould(true);

  /**
   * Ruby controller classes must not hold a direct compile-time reference to any @Entity class.
   * Scoped to ruby: DockerImageController.getManifest() directly calls Tag.getName() — a
   * pre-existing violation in the docker package.
   * allowEmptyShould: passes before any ruby controller classes exist.
   */
  @ArchTest
  static final ArchRule rubyControllersMustNotDependOnEntities =
      noClasses()
          .that()
          .resideInAPackage("..ruby..controllers..")
          .should()
          .dependOnClassesThat()
          .areAnnotatedWith(Entity.class)
          .allowEmptyShould(true);
}
