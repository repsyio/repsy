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
package io.repsy.protocols.pypi.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.pypi.shared.python_package.dtos.PackageUploadForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PypiPublishLimits (RPS-1137)")
class PypiPublishLimitsTest {

  private static String repeat(final char c, final int length) {
    return String.valueOf(c).repeat(length);
  }

  @Nested
  @DisplayName("reject: checkPackageName / checkVersion / checkRequiresPython")
  class Reject {

    @Test
    @DisplayName("accepts a name, version and requires_python at the 255 character limit")
    void acceptsWithinLimit() {
      PypiPublishLimits.checkPackageName(repeat('n', 255));
      PypiPublishLimits.checkVersion(repeat('1', 255));
      PypiPublishLimits.checkRequiresPython(repeat('p', 255));
      PypiPublishLimits.checkVersion(null);
      PypiPublishLimits.checkRequiresPython(null);
    }

    @Test
    @DisplayName("refuses a package name longer than 255 characters")
    void refusesOverLongName() {
      assertThatThrownBy(() -> PypiPublishLimits.checkPackageName(repeat('n', 256)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("pypiPackageNameTooLong");
    }

    @Test
    @DisplayName("refuses a version longer than 255 characters")
    void refusesOverLongVersion() {
      assertThatThrownBy(() -> PypiPublishLimits.checkVersion(repeat('1', 256)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("pypiVersionTooLong");
    }

    @Test
    @DisplayName("accepts an archive file name at the 255 character limit")
    void acceptsArchiveFilenameWithinLimit() {
      PypiPublishLimits.checkArchiveFilename(repeat('a', 255));
    }

    @Test
    @DisplayName("refuses an archive file name longer than 255 characters (RPS-1155)")
    void refusesOverLongArchiveFilename() {
      assertThatThrownBy(() -> PypiPublishLimits.checkArchiveFilename(repeat('a', 256)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("pypiArchiveFileNameTooLong");
    }

    @Test
    @DisplayName("refuses a requires_python longer than 255 characters")
    void refusesOverLongRequiresPython() {
      assertThatThrownBy(() -> PypiPublishLimits.checkRequiresPython(repeat('p', 256)))
          .isInstanceOf(BadRequestException.class)
          .hasMessage("pypiRequiresPythonTooLong");
    }
  }

  @Nested
  @DisplayName("drop: dropOverLongFields")
  class Drop {

    private PackageUploadForm form() {
      return new PackageUploadForm();
    }

    @Test
    @DisplayName("nulls an over-long home_page but leaves a short one alone")
    void dropsOverLongHomePage() {
      final var form = this.form();
      form.setHome_page(repeat('h', 256));
      PypiPublishLimits.dropOverLongFields(form);
      assertThat(form.getHome_page()).isNull();

      final var form2 = this.form();
      form2.setHome_page("https://example.test");
      PypiPublishLimits.dropOverLongFields(form2);
      assertThat(form2.getHome_page()).isEqualTo("https://example.test");
    }

    @Test
    @DisplayName("nulls an over-long author and author_email individually")
    void dropsOverLongAuthorFields() {
      final var form = this.form();
      form.setAuthor(repeat('a', 256));
      form.setAuthor_email("short@example.test");
      PypiPublishLimits.dropOverLongFields(form);
      assertThat(form.getAuthor()).isNull();
      assertThat(form.getAuthor_email()).isEqualTo("short@example.test");
    }

    @Test
    @DisplayName("nulls an over-long license")
    void dropsOverLongLicense() {
      final var form = this.form();
      form.setLicense(repeat('l', 256));
      PypiPublishLimits.dropOverLongFields(form);
      assertThat(form.getLicense()).isNull();
    }

    @Test
    @DisplayName("nulls an over-long description_content_type")
    void dropsOverLongDescriptionContentType() {
      final var form = this.form();
      form.setDescription_content_type(repeat('d', 256));
      PypiPublishLimits.dropOverLongFields(form);
      assertThat(form.getDescription_content_type()).isNull();
    }

    @Test
    @DisplayName("drops only the over-long classifier, keeping the rest of the array")
    void dropsOnlyOverLongClassifierEntry() {
      final var form = this.form();
      form.setClassifiers(
          new String[] {
            "Programming Language :: Python :: 3",
            "Topic :: " + repeat('x', 256),
            repeat('y', 256) + " :: Value",
            "License :: OSI Approved :: MIT License"
          });

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getClassifiers())
          .containsExactly(
              "Programming Language :: Python :: 3", "License :: OSI Approved :: MIT License");
    }

    @Test
    @DisplayName("keeps a blank or malformed classifier entry -- not a length problem")
    void keepsBlankOrMalformedClassifierEntries() {
      final var form = this.form();
      form.setClassifiers(new String[] {"", "no-double-colon", "Programming Language :: Python"});

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getClassifiers())
          .containsExactly("", "no-double-colon", "Programming Language :: Python");
    }

    @Test
    @DisplayName("drops only the project_url whose label is over 32 characters")
    void dropsOnlyOverLongProjectUrlLabel() {
      final var form = this.form();
      form.setProject_urls(
          new String[] {
            "Homepage,https://example.test", repeat('l', 33) + ",https://example.test/other"
          });

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getProject_urls()).containsExactly("Homepage,https://example.test");
    }

    @Test
    @DisplayName("keeps a project_url whose label is at the 32 character limit")
    void keepsProjectUrlLabelAtLimit() {
      final var form = this.form();
      final var entry = repeat('l', 32) + ",https://example.test";
      form.setProject_urls(new String[] {entry});

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getProject_urls()).containsExactly(entry);
    }

    @Test
    @DisplayName("does not guard summary, description or a project_url's own url (unbounded text)")
    void leavesUnboundedTextFieldsUntouched() {
      final var form = this.form();
      form.setSummary(repeat('s', 5000));
      form.setDescription(repeat('d', 5000));
      final var entry = "Homepage," + "https://example.test/" + repeat('u', 5000);
      form.setProject_urls(new String[] {entry});

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getSummary()).hasSize(5000);
      assertThat(form.getDescription()).hasSize(5000);
      assertThat(form.getProject_urls()).containsExactly(entry);
    }

    @Test
    @DisplayName("does nothing to a form with no over-long fields")
    void leavesShortFieldsUntouched() {
      final var form = this.form();
      form.setHome_page("https://example.test");
      form.setAuthor("Barney Rubble");
      form.setLicense("MIT");
      form.setClassifiers(new String[] {"Programming Language :: Python :: 3"});
      form.setProject_urls(new String[] {"Homepage,https://example.test"});

      PypiPublishLimits.dropOverLongFields(form);

      assertThat(form.getHome_page()).isEqualTo("https://example.test");
      assertThat(form.getAuthor()).isEqualTo("Barney Rubble");
      assertThat(form.getLicense()).isEqualTo("MIT");
      assertThat(form.getClassifiers()).containsExactly("Programming Language :: Python :: 3");
      assertThat(form.getProject_urls()).containsExactly("Homepage,https://example.test");
    }
  }
}
