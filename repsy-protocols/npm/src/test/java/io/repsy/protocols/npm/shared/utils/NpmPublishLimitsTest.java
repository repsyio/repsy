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
package io.repsy.protocols.npm.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NpmPublishLimits (RPS-1136)")
class NpmPublishLimitsTest {

  private static String repeat(final char c, final int length) {
    return String.valueOf(c).repeat(length);
  }

  @Nested
  @DisplayName("reject: checkScopeAndName / checkVersion / checkDistTags")
  class Reject {

    @Test
    @DisplayName("accepts a scope and name at or under the limit")
    void acceptsWithinLimit() {
      NpmPublishLimits.checkScopeAndName(repeat('s', 214), repeat('n', 214));
      NpmPublishLimits.checkScopeAndName(null, "demo");
    }

    @Test
    @DisplayName("refuses a scope longer than 214 characters")
    void refusesOverLongScope() {
      final var longScope = repeat('s', 215);

      assertThatThrownBy(() -> NpmPublishLimits.checkScopeAndName(longScope, "demo"))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageScopeTooLong");
    }

    @Test
    @DisplayName("refuses a name longer than 214 characters")
    void refusesOverLongName() {
      final var longName = repeat('n', 215);

      assertThatThrownBy(() -> NpmPublishLimits.checkScopeAndName(null, longName))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageNameTooLong");
    }

    @Test
    @DisplayName("refuses a version longer than 128 characters")
    void refusesOverLongVersion() {
      final var longVersion = repeat('1', 129);

      assertThatThrownBy(() -> NpmPublishLimits.checkVersion(longVersion))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageVersionTooLong");
    }

    @Test
    @DisplayName("accepts a version at the 128 character limit")
    void acceptsVersionAtLimit() {
      NpmPublishLimits.checkVersion(repeat('1', 128));
    }

    @Test
    @DisplayName("refuses an over-long dist-tag name")
    void refusesOverLongDistTagName() {
      final Map<String, Object> payload = Map.of("dist-tags", Map.of(repeat('t', 256), "1.0.0"));

      assertThatThrownBy(() -> NpmPublishLimits.checkDistTags(payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("distTagNameTooLong");
    }

    @Test
    @DisplayName("refuses an over-long dist-tag value (becomes npm_package.latest)")
    void refusesOverLongDistTagValue() {
      final Map<String, Object> payload = Map.of("dist-tags", Map.of("latest", repeat('1', 129)));

      assertThatThrownBy(() -> NpmPublishLimits.checkDistTags(payload))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("packageVersionTooLong");
    }

    @Test
    @DisplayName("accepts dist-tags within both limits, and a missing dist-tags map")
    void acceptsWithinLimitDistTags() {
      NpmPublishLimits.checkDistTags(Map.of("dist-tags", Map.of("latest", "1.0.0")));
      NpmPublishLimits.checkDistTags(Map.of());
    }

    @Test
    @DisplayName("refuses a lone dist-tag name over 255 characters, accepts one at the limit")
    void checksLoneDistTagName() {
      NpmPublishLimits.checkDistTagName(repeat('t', 255));

      final var longTag = repeat('t', 256);

      assertThatThrownBy(() -> NpmPublishLimits.checkDistTagName(longTag))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("distTagNameTooLong");
    }

    @Test
    @DisplayName("refuses a deprecation message over 4096 characters, accepts one at the limit")
    void checksDeprecationMessage() {
      NpmPublishLimits.checkDeprecationMessage(repeat('m', 4096));
      NpmPublishLimits.checkDeprecationMessage("");

      final var longMessage = repeat('m', 4097);

      assertThatThrownBy(() -> NpmPublishLimits.checkDeprecationMessage(longMessage))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("deprecationMessageTooLong");
    }
  }

  @Nested
  @DisplayName("drop: dropOverLongFields")
  class Drop {

    private Map<String, Object> version() {
      return new LinkedHashMap<>();
    }

    @Test
    @DisplayName("nulls an over-long homepage but leaves a short one alone")
    void dropsOverLongHomepage() {
      final var version = this.version();
      version.put("homepage", repeat('h', 256));

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(version.get("homepage")).isNull();

      final var version2 = this.version();
      version2.put("homepage", "https://example.test");
      NpmPublishLimits.dropOverLongFields(version2);
      assertThat(version2).containsEntry("homepage", "https://example.test");
    }

    @Test
    @DisplayName("nulls an over-long string license, and an over-long license.type")
    void dropsOverLongLicense() {
      final var version = this.version();
      version.put("license", repeat('l', 256));
      NpmPublishLimits.dropOverLongFields(version);
      assertThat(version.get("license")).isNull();

      final var mapLicense = this.version();
      final Map<String, Object> license = new HashMap<>();
      license.put("type", repeat('l', 256));
      mapLicense.put("license", license);
      NpmPublishLimits.dropOverLongFields(mapLicense);
      assertThat(license.get("type")).isNull();
    }

    @Test
    @DisplayName("nulls only the over-long field of author, keeping the others")
    void dropsOverLongAuthorFieldsIndividually() {
      final var version = this.version();
      final Map<String, Object> author = new HashMap<>();
      author.put("name", "Barney Rubble");
      author.put("email", repeat('e', 256));
      author.put("url", "https://example.test");
      version.put("author", author);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(author).containsEntry("name", "Barney Rubble");
      assertThat(author.get("email")).isNull();
      assertThat(author).containsEntry("url", "https://example.test");
    }

    @Test
    @DisplayName("nulls over-long bugs url/email")
    void dropsOverLongBugs() {
      final var version = this.version();
      final Map<String, Object> bugs = new HashMap<>();
      bugs.put("url", repeat('u', 256));
      bugs.put("email", repeat('e', 256));
      version.put("bugs", bugs);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(bugs.get("url")).isNull();
      assertThat(bugs.get("email")).isNull();
    }

    @Test
    @DisplayName("nulls over-long repository type/url")
    void dropsOverLongRepository() {
      final var version = this.version();
      final Map<String, Object> repository = new HashMap<>();
      repository.put("type", repeat('t', 256));
      repository.put("url", "https://example.test/repo.git");
      version.put("repository", repository);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(repository.get("type")).isNull();
      assertThat(repository).containsEntry("url", "https://example.test/repo.git");
    }

    @Test
    @DisplayName("drops only the over-long keyword, keeping the rest of the array")
    void dropsOnlyOverLongKeywordEntry() {
      final var version = this.version();
      final List<String> keywords = new ArrayList<>(List.of("alpha", repeat('k', 256), "beta"));
      version.put("keywords", keywords);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(keywords).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("drops the whole maintainer entry when its name is over-long (NOT NULL column)")
    void dropsWholeMaintainerEntryForOverLongName() {
      final var version = this.version();
      final Map<String, Object> good = new HashMap<>(Map.of("name", "ok-maintainer"));
      final Map<String, Object> bad = new HashMap<>(Map.of("name", repeat('m', 256)));
      final List<Map<String, Object>> maintainers = new ArrayList<>(List.of(good, bad));
      version.put("maintainers", maintainers);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(maintainers).containsExactly(good);
    }

    @Test
    @DisplayName("drops a maintainer entry without a usable name (NOT NULL column, RPS-2186)")
    void dropsMaintainerEntryWithoutName() {
      final var version = this.version();
      final Map<String, Object> good = new HashMap<>(Map.of("name", "ok-maintainer"));
      final Map<String, Object> missing = new HashMap<>(Map.of("email", "a@example.test"));
      final Map<String, Object> nullName = new HashMap<>();
      nullName.put("name", null);
      nullName.put("email", "b@example.test");
      final Map<String, Object> notAString = new HashMap<>(Map.of("name", 42));
      final List<Map<String, Object>> maintainers =
          new ArrayList<>(List.of(good, missing, nullName, notAString));
      version.put("maintainers", maintainers);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(maintainers).containsExactly(good);
    }

    @Test
    @DisplayName("nulls only the over-long email/url of a maintainer, keeping the entry and name")
    void dropsOverLongMaintainerEmailOrUrlOnly() {
      final var version = this.version();
      final Map<String, Object> maintainer = new HashMap<>();
      maintainer.put("name", "ok-maintainer");
      maintainer.put("email", repeat('e', 256));
      maintainer.put("url", "https://example.test");
      final List<Map<String, Object>> maintainers = new ArrayList<>(List.of(maintainer));
      version.put("maintainers", maintainers);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(maintainers).hasSize(1);
      assertThat(maintainer).containsEntry("name", "ok-maintainer");
      assertThat(maintainer.get("email")).isNull();
      assertThat(maintainer).containsEntry("url", "https://example.test");
    }

    @Test
    @DisplayName("nulls a non-string maintainer email or url, keeping the entry (RPS-2199)")
    void nullsNonStringMaintainerEmailOrUrl() {
      final var version = this.version();
      final Map<String, Object> maintainer = new HashMap<>();
      maintainer.put("name", "ok-maintainer");
      maintainer.put("email", 42);
      maintainer.put("url", Map.of("href", "https://example.test"));
      final List<Map<String, Object>> maintainers = new ArrayList<>(List.of(maintainer));
      version.put("maintainers", maintainers);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(maintainers).hasSize(1);
      assertThat(maintainer).containsEntry("name", "ok-maintainer");
      assertThat(maintainer.get("email")).isNull();
      assertThat(maintainer.get("url")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("does nothing to a version with no over-long fields")
    void leavesShortFieldsUntouched() {
      final var version = this.version();
      version.put("homepage", "https://example.test");
      version.put("license", "MIT");
      version.put("keywords", new ArrayList<>(List.of("a", "b")));

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(version)
          .containsEntry("homepage", "https://example.test")
          .containsEntry("license", "MIT");
      assertThat((List<String>) version.get("keywords")).containsExactly("a", "b");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName(
        "nulls every descriptive value that is not a string, keeping the publish (RPS-2210)")
    void nullsNonStringDescriptiveValues() {
      final var version = this.version();
      version.put("description", 42);
      version.put("homepage", List.of("x"));
      version.put("license", new HashMap<>(Map.of("type", 1)));
      version.put("author", new HashMap<>(Map.of("name", 1, "email", Map.of(), "url", "u")));
      version.put("bugs", 7);
      version.put("repository", new HashMap<>(Map.of("type", true, "url", "git://x")));

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(version.get("description")).isNull();
      assertThat(version.get("homepage")).isNull();
      assertThat(((Map<String, Object>) version.get("license")).get("type")).isNull();
      final var author = (Map<String, Object>) version.get("author");
      assertThat(author.get("name")).isNull();
      assertThat(author.get("email")).isNull();
      assertThat(author).containsEntry("url", "u");
      assertThat(version.get("bugs")).isNull();
      final var repository = (Map<String, Object>) version.get("repository");
      assertThat(repository.get("type")).isNull();
      assertThat(repository).containsEntry("url", "git://x");
    }

    @Test
    @DisplayName("keeps the string form of a license, an author and a repository (RPS-2210)")
    void keepsStringForms() {
      final var version = this.version();
      version.put("license", "MIT");
      version.put("author", "Jane <jane@example.org>");
      version.put("repository", "github:a/b");

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(version)
          .containsEntry("license", "MIT")
          .containsEntry("author", "Jane <jane@example.org>")
          .containsEntry("repository", "github:a/b");
    }

    @Test
    @DisplayName("drops non-string keywords and non-object maintainers, nulls non-lists (RPS-2210)")
    void dropsNonStringEntries() {
      final var version = this.version();
      final var keywords = new ArrayList<Object>(java.util.Arrays.asList("ok", 1, null, Map.of()));
      final var maintainers =
          new ArrayList<Object>(java.util.Arrays.asList(5, "x", null, Map.of("name", "jane")));
      version.put("keywords", keywords);
      version.put("maintainers", maintainers);

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(keywords).containsExactly("ok");
      assertThat(maintainers).containsExactly(Map.of("name", "jane"));

      version.put("keywords", 42);
      version.put("maintainers", Map.of("name", "jane"));

      NpmPublishLimits.dropOverLongFields(version);

      assertThat(version.get("keywords")).isNull();
      assertThat(version.get("maintainers")).isNull();
    }
  }
}
