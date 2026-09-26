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
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PluginDescriptorReader (RPS-1458)")
class PluginDescriptorReaderTest {

  private static final String ARTIFACT_ID = "foo-maven-plugin";

  private static String descriptor(final String artifactId, final String goalPrefix) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plugin>\n  <name>Foo</name>\n"
        + "  <description></description>\n  <groupId>com.acme</groupId>\n"
        + "  <artifactId>"
        + artifactId
        + "</artifactId>\n  <version>1.0</version>\n  <goalPrefix>"
        + goalPrefix
        + "</goalPrefix>\n  <mojos><mojo><goal>hi</goal></mojo></mojos>\n</plugin>\n";
  }

  private static byte[] zip(final Map<String, byte[]> entries) throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(bytes)) {
      for (final var entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }

    return bytes.toByteArray();
  }

  private static byte[] jarWith(final String descriptor) throws IOException {
    return zip(Map.of(PluginDescriptorReader.DESCRIPTOR_ENTRY, descriptor.getBytes(UTF_8)));
  }

  private static String read(final byte[] jar) {
    return PluginDescriptorReader.goalPrefix(new ByteArrayInputStream(jar), ARTIFACT_ID);
  }

  @Test
  @DisplayName("reads the goalPrefix of the descriptor")
  void readsTheGoalPrefix() throws IOException {
    assertThat(read(jarWith(descriptor(ARTIFACT_ID, "custom")))).isEqualTo("custom");
  }

  @Test
  @DisplayName("trims the value and accepts the characters a prefix is written with")
  void trimsTheValue() throws IOException {
    assertThat(read(jarWith(descriptor(ARTIFACT_ID, "  k8s.v2_x-y \n")))).isEqualTo("k8s.v2_x-y");
  }

  @Test
  @DisplayName("finds the descriptor after many other entries")
  void findsTheDescriptorLateInTheJar() throws IOException {
    final var entries = new java.util.LinkedHashMap<String, byte[]>();

    for (var i = 0; i < 200; i++) {
      entries.put("com/acme/Class" + i + ".class", new byte[2048]);
    }

    entries.put(
        PluginDescriptorReader.DESCRIPTOR_ENTRY, descriptor(ARTIFACT_ID, "late").getBytes(UTF_8));

    assertThat(read(zip(entries))).isEqualTo("late");
  }

  @Test
  @DisplayName("a jar without a descriptor has no prefix, and neither has a nested one")
  void aJarWithoutADescriptor() throws IOException {
    assertThat(read(zip(Map.of("com/acme/A.class", new byte[8])))).isNull();
    assertThat(
            read(
                zip(
                    Map.of(
                        "sub/" + PluginDescriptorReader.DESCRIPTOR_ENTRY,
                        descriptor(ARTIFACT_ID, "nested").getBytes(UTF_8)))))
        .isNull();
  }

  @Test
  @DisplayName("something that is not a zip, an empty stream and a truncated jar have no prefix")
  void notAZip() throws IOException {
    final var jar = jarWith(descriptor(ARTIFACT_ID, "custom"));

    assertThat(read("this is not a jar".getBytes(UTF_8))).isNull();
    assertThat(read(new byte[0])).isNull();
    assertThat(read(java.util.Arrays.copyOf(jar, jar.length / 2))).isNull();
  }

  @Test
  @DisplayName("a descriptor that is not XML has no prefix")
  void malformedDescriptor() throws IOException {
    assertThat(read(jarWith("<plugin><goalPrefix>oops"))).isNull();
    assertThat(read(jarWith("not xml at all"))).isNull();
    assertThat(read(jarWith("<other><goalPrefix>custom</goalPrefix></other>"))).isNull();
  }

  @Test
  @DisplayName("a descriptor of another artifact is ignored")
  void anotherArtifactsDescriptor() throws IOException {
    assertThat(read(jarWith(descriptor("shaded-maven-plugin", "foreign")))).isNull();
  }

  @Test
  @DisplayName("a goalPrefix inside the mojos, or none at all, is not taken")
  void goalPrefixOutsideThePluginElementIsNotTaken() throws IOException {
    assertThat(
            read(
                jarWith(
                    "<plugin><artifactId>foo-maven-plugin</artifactId><mojos><mojo>"
                        + "<goalPrefix>inner</goalPrefix></mojo></mojos></plugin>")))
        .isNull();
    assertThat(read(jarWith("<plugin><artifactId>foo-maven-plugin</artifactId></plugin>")))
        .isNull();
    assertThat(
            read(
                jarWith(
                    "<plugin><artifactId>foo-maven-plugin</artifactId><x><goalPrefix>deep"
                        + "</goalPrefix></x></plugin>")))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "a b", "-lead", ".lead", "a:b", "a/b", "a<b", "é"})
  @DisplayName("a value that cannot be written as prefix:goal is ignored")
  void invalidValues(final String value) throws IOException {
    assertThat(read(jarWith(descriptor(ARTIFACT_ID, value)))).isNull();
  }

  @Test
  @DisplayName("a prefix of 150 characters is taken and one of 151 is not")
  void lengthBound() throws IOException {
    assertThat(read(jarWith(descriptor(ARTIFACT_ID, "a".repeat(150))))).hasSize(150);
    assertThat(read(jarWith(descriptor(ARTIFACT_ID, "a".repeat(151))))).isNull();
  }

  @Test
  @DisplayName("a descriptor longer than the cap is read up to the goalPrefix, which is up front")
  void aLargeDescriptorIsNotBuffered() throws IOException {
    final var big =
        descriptor(ARTIFACT_ID, "front")
            .replace("<mojos>", "<mojos><!-- " + "x".repeat(3 * 1024 * 1024) + " -->");

    assertThat(read(jarWith(big))).isEqualTo("front");
  }

  @Test
  @DisplayName("a descriptor whose goalPrefix is past the cap has no prefix")
  void aGoalPrefixPastTheCap() throws IOException {
    final var padded =
        "<plugin><artifactId>foo-maven-plugin</artifactId><!-- "
            + "x".repeat(PluginDescriptorReader.MAX_DESCRIPTOR_BYTES)
            + " --><goalPrefix>late</goalPrefix></plugin>";

    assertThat(read(jarWith(padded))).isNull();
  }

  @Test
  @DisplayName("a jar that inflates past the bound is given up on")
  void anInflationBomb() throws IOException {
    final var entries = new java.util.LinkedHashMap<String, byte[]>();
    entries.put(
        "bomb.bin", new byte[(int) (PluginDescriptorReader.MAX_INFLATED_BYTES / 1024 + 1) * 1024]);
    entries.put(
        PluginDescriptorReader.DESCRIPTOR_ENTRY, descriptor(ARTIFACT_ID, "custom").getBytes(UTF_8));

    assertThat(read(zip(entries))).isNull();
  }

  @Test
  @DisplayName("a jar with more entries than the bound is given up on")
  void tooManyEntries() throws IOException {
    final var bytes = new ByteArrayOutputStream();

    try (final var zip = new ZipOutputStream(bytes)) {
      for (var i = 0; i <= PluginDescriptorReader.MAX_ENTRIES; i++) {
        zip.putNextEntry(new ZipEntry("e" + i));
        zip.closeEntry();
      }

      zip.putNextEntry(new ZipEntry(PluginDescriptorReader.DESCRIPTOR_ENTRY));
      zip.write(descriptor(ARTIFACT_ID, "custom").getBytes(UTF_8));
      zip.closeEntry();
    }

    assertThat(read(bytes.toByteArray())).isNull();
  }

  @Test
  @DisplayName("a jar longer than the scan bound is given up on")
  void tooManyBytes() {
    final InputStream endless =
        new InputStream() {
          private long served;

          @Override
          public int read() {
            return served++ % 4 == 0 ? 'P' : 'K';
          }

          @Override
          public int read(final byte[] b, final int off, final int len) {
            for (var i = 0; i < len; i++) {
              b[off + i] = (byte) read();
            }

            return len;
          }
        };

    assertThat(PluginDescriptorReader.goalPrefix(endless, ARTIFACT_ID)).isNull();
  }

  @Test
  @DisplayName("a DTD with an external entity is not resolved")
  void externalEntityIsNotResolved(@TempDir final Path dir) throws IOException {
    final var secret = dir.resolve("secret.txt");
    Files.writeString(secret, "leaked");

    final var xml =
        "<?xml version=\"1.0\"?><!DOCTYPE plugin [<!ENTITY xxe SYSTEM \""
            + secret.toUri()
            + "\">]><plugin><artifactId>foo-maven-plugin</artifactId>"
            + "<goalPrefix>&xxe;</goalPrefix></plugin>";

    assertThat(read(jarWith(xml))).isNull();
  }

  @Test
  @DisplayName("a DTD that defines an internal entity is not expanded either")
  void internalEntityIsNotExpanded() throws IOException {
    final var xml =
        "<?xml version=\"1.0\"?><!DOCTYPE plugin [<!ENTITY p \"expanded\">]>"
            + "<plugin><artifactId>foo-maven-plugin</artifactId><goalPrefix>&p;</goalPrefix>"
            + "</plugin>";

    assertThat(read(jarWith(xml))).isNotEqualTo("expanded");
  }
}
