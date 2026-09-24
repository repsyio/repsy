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
package io.repsy.protocols.docker.shared.tag.services;

import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.tag.dtos.BaseManifestDetail;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/**
 * The manifests of a Docker repo. A manifest is content-addressed: one row per image and digest,
 * kept for as long as something (a tag, an index, or nobody) still has it, and pullable by its
 * digest whatever tags point at it. A tag is a movable pointer to a manifest.
 */
@NullMarked
public interface ManifestService<ID> {

  /**
   * Refuses an index that references a manifest the image does not have, before anything of the
   * index is written.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code manifestNotFound}
   *     for the first digest (of either algorithm) that is no manifest of the image
   */
  void verifyManifestsExist(ID repoId, ID imageId, List<String> digests);

  /**
   * Stores the manifest list (index) and replaces the manifests it references. When the form's
   * reference is a tag, the tag is created or moved to the index.
   */
  void createManifestList(ID repoId, ID imageId, TagForm tagForm);

  /**
   * Stores an image manifest under its digest, once per image, and, when the form's reference is a
   * tag, creates the tag or moves it to the manifest. The manifest the tag pointed at before stays
   * stored, untagged.
   */
  void createSinglePlatformManifest(ID repoId, BaseImageInfo<ID> imageInfo, TagForm tagForm);

  Optional<BaseTagDetail<ID>> findActiveTagByNameAndRepoAndImage(
      ID repoId, String imageName, String tag);

  /**
   * Finds a manifest of the image by its digest, of either algorithm, however it got there: pushed
   * by tag, pushed by digest, no longer tagged, or referenced by an index.
   *
   * @throws io.repsy.core.error_handling.exceptions.ItemNotFoundException {@code manifestNotFound}
   */
  BaseManifestDetail<ID> findManifestByRepoIdAndImageNameAndDigest(
      ID repoId, BaseImageInfo<ID> imageInfo, String digest);
}
