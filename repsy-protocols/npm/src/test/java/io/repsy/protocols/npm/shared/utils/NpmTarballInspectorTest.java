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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1300: {@link NpmTarballInspector} reads the manifest and the digests of a tarball, whatever
 * else the archive holds, and never lets a tarball that is not a tar.gz stop a rebuild.
 */
@DisplayName("NpmTarballInspector (RPS-1300)")
class NpmTarballInspectorTest {

  private static final String MANIFEST =
      "{\"name\":\"demo\",\"version\":\"1.0.0\",\"dependencies\":{\"a\":\"^1\"},\"main\":\"i.js\"}";

  /** A gzipped tar with the given (path, content) entries, in order. */
  @SafeVarargs
  private static byte[] tarGz(final Map.Entry<String, String>... entries) throws IOException {
    final var out = new ByteArrayOutputStream();

    try (final var gzip = new GzipCompressorOutputStream(out);
        final var tar = new TarArchiveOutputStream(gzip)) {
      for (final var file : entries) {
        final var content = file.getValue().getBytes(StandardCharsets.UTF_8);
        final var entry = new TarArchiveEntry(file.getKey());
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        tar.write(content);
        tar.closeArchiveEntry();
      }
      tar.finish();
    }

    return out.toByteArray();
  }

  private static NpmTarballFacts inspect(final byte[] tarball) throws IOException {
    return NpmTarballInspector.inspect(new ByteArrayInputStream(tarball));
  }

  @Test
  @DisplayName("reads the package.json under the top directory of the archive")
  void readsTheManifest() throws Exception {
    final var facts =
        inspect(
            tarGz(
                Map.entry("package/README.md", "hello"),
                Map.entry("package/package.json", MANIFEST),
                Map.entry("package/index.js", "1")));

    assertThat(facts.manifest())
        .containsEntry("name", "demo")
        .containsEntry("main", "i.js")
        .containsEntry("dependencies", Map.of("a", "^1"));
  }

  @Test
  @DisplayName("takes the manifest whatever the top directory is called")
  void anyTopDirectory() throws Exception {
    final var facts = inspect(tarGz(Map.entry("demo-1.0.0/package.json", MANIFEST)));

    assertThat(facts.manifest()).containsEntry("name", "demo");
  }

  @Test
  @DisplayName("ignores a package.json that is deeper in the archive")
  void ignoresNestedManifests() throws Exception {
    final var facts =
        inspect(
            tarGz(
                Map.entry("package/node_modules/x/package.json", "{\"name\":\"x\"}"),
                Map.entry("package/package.json", MANIFEST)));

    assertThat(facts.manifest()).containsEntry("name", "demo");
  }

  @Test
  @DisplayName("an archive without a package.json has an empty manifest but still has digests")
  void noManifest() throws Exception {
    final var tarball = tarGz(Map.entry("package/index.js", "1"));

    final var facts = inspect(tarball);

    assertThat(facts.manifest()).isEmpty();
    assertThat(facts.shasum()).isEqualTo(DigestUtils.sha1Hex(tarball));
  }

  @Test
  @DisplayName("the digests cover the whole tarball, and the integrity is SHA-512 in SRI form")
  void digests() throws Exception {
    final var tarball = tarGz(Map.entry("package/package.json", MANIFEST));

    final var facts = inspect(tarball);

    assertThat(facts.shasum()).isEqualTo(DigestUtils.sha1Hex(tarball));
    assertThat(facts.integrity())
        .isEqualTo("sha512-" + Base64.getEncoder().encodeToString(DigestUtils.sha512(tarball)));
  }

  @Test
  @DisplayName("the digests cover bytes after the end of the archive too")
  void digestsCoverTrailingBytes() throws Exception {
    final var archive = tarGz(Map.entry("package/package.json", MANIFEST));
    final var tarball = new byte[archive.length + 5000];
    System.arraycopy(archive, 0, tarball, 0, archive.length);

    final var facts = inspect(tarball);

    assertThat(facts.manifest()).containsEntry("name", "demo");
    assertThat(facts.shasum()).isEqualTo(DigestUtils.sha1Hex(tarball));
  }

  @Test
  @DisplayName("bytes that are not a tar.gz give an empty manifest and the digests of the bytes")
  void notAnArchive() throws Exception {
    final var bytes = "tarball of 1.0.0".getBytes(StandardCharsets.UTF_8);

    final var facts = inspect(bytes);

    assertThat(facts.manifest()).isEmpty();
    assertThat(facts.shasum()).isEqualTo(DigestUtils.sha1Hex(bytes));
  }

  @Test
  @DisplayName("a manifest that is not a JSON object is ignored")
  void manifestThatIsNotAnObject() throws Exception {
    final var facts = inspect(tarGz(Map.entry("package/package.json", "[1,2]")));

    assertThat(facts.manifest()).isEmpty();
  }

  @Test
  @DisplayName("a manifest that is not JSON is ignored")
  void manifestThatIsNotJson() throws Exception {
    final var facts = inspect(tarGz(Map.entry("package/package.json", "{nope")));

    assertThat(facts.manifest()).isEmpty();
  }

  @Test
  @DisplayName("a manifest above the size limit is not read")
  void hugeManifest() throws Exception {
    final var big = new LinkedHashMap<String, String>();
    big.put("x", "y".repeat((1 << 20) + 10));
    final var json = "{\"x\":\"" + big.get("x") + "\"}";

    final var facts = inspect(tarGz(Map.entry("package/package.json", json)));

    assertThat(facts.manifest()).isEmpty();
  }

  @Test
  @DisplayName("a stream that cannot be read fails, as no digest could be given")
  void unreadableStream() {
    final InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("disk gone");
          }
        };

    assertThatThrownBy(() -> NpmTarballInspector.inspect(broken))
        .isInstanceOf(IOException.class)
        .hasMessage("disk gone");
  }
}
