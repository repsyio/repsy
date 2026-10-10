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

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The naming rules of "Java naming" in AGENTS.md (RPS-2029) as ArchUnit rules. Each rule is a plain
 * {@link ArchRule}: {@link NamingArchRuleTest} applies them to the OS backend and the protocol
 * libraries with a shrink-only freeze list, and to planted violations to prove each one fails.
 */
final class NamingRules {

  /** A package segment is lowercase; an underscore between words is allowed (RPS-2019). */
  static final Pattern PACKAGE_SEGMENT = Pattern.compile("[a-z][a-z0-9_]*");

  static final ArchRule CONTROLLERS = suffix("controllers", "Controller");
  static final ArchRule REPOSITORIES = suffix("repositories", "Repository");
  static final ArchRule UTILS = suffix("utils", "Utils");
  static final ArchRule LISTENERS = suffix("listeners", "Listener");
  static final ArchRule SERVICES = suffix("services", "Service");

  static final ArchRule CONFIGS =
      ArchRuleDefinition.classes()
          .that(topLevelIn("configs"))
          .should(
              nameEndsWith(
                  "'Config' or 'Properties'",
                  name -> name.endsWith("Config") || name.endsWith("Properties")))
          .as("classes in a configs package should have a name ending in Config or Properties");

  static final ArchRule CONFIGURATION_PROPERTIES =
      ArchRuleDefinition.classes()
          .that()
          .areAnnotatedWith(ConfigurationProperties.class)
          .and()
          .areTopLevelClasses()
          .should(nameEndsWith("'Properties'", name -> name.endsWith("Properties")))
          .as("@ConfigurationProperties classes should have a name ending in Properties");

  static final ArchRule PACKAGES =
      ArchRuleDefinition.classes()
          .that(DescribedPredicate.describe("are real types", NamingRules::isRealType))
          .should(
              new ArchCondition<JavaClass>("be in a package whose segments match [a-z][a-z0-9_]*") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                  for (String segment : item.getPackageName().split("\\.")) {
                    if (!segment.isEmpty() && !PACKAGE_SEGMENT.matcher(segment).matches()) {
                      events.add(violated(item, item.getPackageName()));
                      return;
                    }
                  }
                }
              })
          .as("package names should be lowercase, digits and underscores");

  private NamingRules() {}

  private static ArchRule suffix(String layerPackage, String suffix) {
    return ArchRuleDefinition.classes()
        .that(topLevelIn(layerPackage))
        .should(nameEndsWith("'" + suffix + "'", name -> name.endsWith(suffix)))
        .as("classes in a " + layerPackage + " package should have a name ending in " + suffix);
  }

  private static DescribedPredicate<JavaClass> topLevelIn(String layerPackage) {
    return DescribedPredicate.describe(
        "are top level types in a " + layerPackage + " package",
        c ->
            isRealType(c)
                && !c.isNestedClass()
                && c.getPackageName().matches("(.*\\.)?" + layerPackage + "(\\..*)?"));
  }

  private static boolean isRealType(JavaClass c) {
    return !c.isAnonymousClass() && !c.getSimpleName().equals("package-info");
  }

  private static ArchCondition<JavaClass> nameEndsWith(
      String what, java.util.function.Predicate<String> ok) {
    return new ArchCondition<JavaClass>("have a name ending in " + what) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        if (!ok.test(item.getSimpleName())) {
          events.add(violated(item, item.getName()));
        }
      }
    };
  }
}
