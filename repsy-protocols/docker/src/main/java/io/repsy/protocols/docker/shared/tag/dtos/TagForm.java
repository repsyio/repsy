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
package io.repsy.protocols.docker.shared.tag.dtos;

import io.repsy.protocols.shared.utils.BlobDigests;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TagForm {
  private static final String MULTIPLATFORM = "Multiplatform";

  private String imageName;
  private String manifestDigest;
  private String manifestDigestSha512;
  private String tag;
  private String mediaType;
  private ManifestInfo manifestInfo;
  private ManifestList manifestList;
  private String manifestJson;
  private String platform;

  public static TagForm of(
      final ManifestForm form,
      final String imageName,
      final String platform,
      final ManifestInfo manifestInfo) {

    return TagForm.builder()
        .imageName(imageName)
        .tag(form.getTagName())
        .manifestDigest(form.getDigest())
        .manifestDigestSha512(form.getDigestSha512())
        .mediaType(form.getContentType())
        .manifestInfo(manifestInfo)
        .manifestJson(form.getManifestJson())
        .platform(platform)
        .build();
  }

  public static TagForm of(
      final ManifestForm form,
      final String imageName,
      final String platform,
      final ManifestList manifestList) {

    return TagForm.builder()
        .imageName(imageName)
        .tag(form.getTagName())
        .manifestDigest(form.getDigest())
        .manifestDigestSha512(form.getDigestSha512())
        .mediaType(form.getContentType())
        .manifestList(manifestList)
        .manifestJson(form.getManifestJson())
        .platform(platform)
        .build();
  }

  public List<String> getManifestDigests() {

    return this.manifestList.getManifests().stream().map(ManifestListManifest::getDigest).toList();
  }

  private String getManifestListMediaType() {

    return this.manifestList.getMediaType();
  }

  public String getCalculatedMediaType() {

    final var isMultiPlatform = this.getPlatform().equals(MULTIPLATFORM);

    return isMultiPlatform ? this.getManifestListMediaType() : this.getMediaType();
  }

  /**
   * Tells whether the manifest was pushed under a tag. A manifest pushed by a digest reference
   * ({@code sha256:} or {@code sha512:}) is stored without a tag: it is pullable by its digest, and
   * an index or a later tag can point at it.
   */
  public boolean isTagReference() {

    return !BlobDigests.startsWithDigestPrefix(this.tag);
  }

  public boolean isMultiPlatformByName() {

    return this.getPlatform().equals(MULTIPLATFORM);
  }
}
