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
package io.repsy.protocols.oci.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OCI route paths")
class OciPathUtilsTest {

  private static final String UUID = "0f8fad5b-d9cb-469f-a165-70867728950e";

  @Test
  @DisplayName("an upload start is the uploads collection, with or without the trailing slash")
  void uploadStart() {
    assertThat(OciPathUtils.UPLOAD_START.matcher("/app/blobs/uploads/").matches()).isTrue();
    assertThat(OciPathUtils.UPLOAD_START.matcher("/app/blobs/uploads").matches()).isTrue();
    assertThat(OciPathUtils.UPLOAD_START.matcher("/app/blobs/uploads/" + UUID).matches()).isFalse();
  }

  @Test
  @DisplayName("an upload session names the image and a 36 character id")
  void uploadSession() {
    final var matcher = OciPathUtils.UPLOAD_SESSION.matcher("/app/blobs/uploads/" + UUID + "/");

    assertThat(matcher.matches()).isTrue();
    assertThat(matcher.group(1)).isEqualTo("app");
    assertThat(matcher.group(2)).isEqualTo(UUID);
    assertThat(OciPathUtils.UPLOAD_SESSION.matcher("/app/blobs/uploads/short").matches()).isFalse();
  }

  @Test
  @DisplayName("a manifest path takes any reference, a blob path only the format's digests")
  void manifestAndBlob() {
    final var manifest = OciPathUtils.MANIFEST.matcher("/app/manifests/1.0.0");
    final var blob = OciPathUtils.blob("sha256:[0-9a-f]{64}");
    final var digest = "sha256:" + "a".repeat(64);

    assertThat(manifest.matches()).isTrue();
    assertThat(manifest.group(2)).isEqualTo("1.0.0");
    assertThat(blob.matcher("/app/blobs/" + digest).matches()).isTrue();
    assertThat(blob.matcher("/app/blobs/" + digest + "/").matches()).isTrue();
    assertThat(blob.matcher("/app/blobs/sha512:" + "a".repeat(128)).matches()).isFalse();
  }
}
