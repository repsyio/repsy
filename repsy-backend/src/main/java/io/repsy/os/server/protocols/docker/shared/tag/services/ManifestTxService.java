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
package io.repsy.os.server.protocols.docker.shared.tag.services;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.layer.dtos.ManifestListItem;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.ImageTagListItem;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.TagDetail;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.manifest.ManifestDetail;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.entities.TagPlatform;
import io.repsy.os.server.protocols.docker.shared.tag.mappers.ManifestConverter;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagPlatformRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestListManifestInfo;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.MediaTypes;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ManifestTxService implements ManifestService<UUID> {

  private static final String MULTIPLATFORM = "Multiplatform";
  private static final int RETRY_COUNT = 3;
  private static final long WAIT_RETRY = 100;

  private final ManifestConverter manifestConverter;
  private final ImageRepository imageRepository;
  private final LayerRepository layerRepository;
  private final ManifestRepository manifestRepository;
  private final TagRepository tagRepository;
  private final TagPlatformRepository tagPlatformRepository;

  @Override
  @Retryable(
      retryFor = ObjectOptimisticLockingFailureException.class,
      backoff = @Backoff(delay = 50))
  @Transactional
  public void createManifestList(
      final UUID repoId,
      final UUID imageId,
      final TagForm tagForm,
      final List<ManifestListManifestInfo> manifestInfo) {

    final var image = this.findImageById(imageId);
    final var tag = this.findOrCreateTag(repoId, image, tagForm, 1);

    final var tagPlatform =
        this.findOrCreateTagPlatform(repoId, imageId, tag, tagForm.getPlatform(), 1);

    final var platformManifests =
        this.manifestRepository.findByRepoIdAndImageIdAndDigest(
            repoId, imageId, tagForm.getManifestDigests());

    this.deleteOldManifests(repoId, imageId, tag, platformManifests);
    this.createManifestList(repoId, imageId, tagForm, tagPlatform);
    this.createManifestsAndTagPlatforms(repoId, imageId, tag, manifestInfo);
  }

  @Override
  @Retryable(
      retryFor = ObjectOptimisticLockingFailureException.class,
      backoff = @Backoff(delay = 50))
  @Transactional
  public void createSinglePlatformManifest(
      final UUID repoId, final BaseImageInfo<UUID> baseImageInfo, final TagForm tagForm) {

    final var imageInfo = (ImageInfo) baseImageInfo;

    final var layers = this.findLayersByRepoIdAndForm(tagForm, repoId);
    final var configLayer = this.findConfigLayerByRepoIdAndDigest(tagForm, repoId);

    this.createManifest(repoId, imageInfo, tagForm, layers, configLayer);
  }

  @Override
  public Optional<BaseTagDetail<UUID>> findActiveTagByNameAndRepoAndImage(
      final UUID repoId, final String imageName, final String tag) {

    final var tagOpt =
        this.tagRepository.findByImageRepoIdAndImageNameAndName(repoId, imageName, tag);

    return tagOpt.map(this::mapToTagDetailWithConfigDigest);
  }

  @Override
  public ManifestDetail findManifestByRepoIdAndImageNameAndDigest(
      final UUID repoId, final BaseImageInfo<UUID> imageInfo, final String digest) {

    final var manifests =
        this.manifestRepository.findByRepoIdAndImageIdAndDigestList(
            repoId, imageInfo.getId(), digest);

    if (manifests.isEmpty()) {
      throw new ItemNotFoundException("manifestNotFound");
    }

    return this.manifestConverter.toManifestDetail(manifests.getFirst());
  }

  @Override
  public List<ManifestDetail> findManifests(
      final UUID repoId, final UUID imageId, final UUID tagId) {

    final var manifests =
        this.manifestRepository.findAllByRepoIdAndImageIdAndTagId(repoId, imageId, tagId);

    return manifests.stream().map(this.manifestConverter::toManifestDetail).toList();
  }

  @Override
  public boolean findByDigestAndImageIdAndRepoId(
      final String digest, final UUID imageId, final UUID repoId) {

    return this.manifestRepository
        .findAllNonBindingManifestsByDigest(repoId, imageId, digest)
        .stream()
        .findFirst()
        .isPresent();
  }

  @Transactional
  public void deleteTag(final Tag tag) {

    final var tagPlatforms = this.tagPlatformRepository.findAllByTagId(tag.getId());

    final var manifests = tagPlatforms.stream().flatMap(tp -> tp.getManifests().stream()).toList();

    for (final var manifest : manifests) {
      manifest.getLayers().clear();
    }

    this.tagRepository.delete(tag);
  }

  @Transactional
  @SuppressWarnings("all")
  public void deleteTagsByImageInfo(final UUID repoId, final UUID imageId) {

    final var tags = this.tagRepository.findAllByImageRepoIdAndImageId(repoId, imageId);

    // Do not change this with delete all because it uses aspectj
    for (final var tag : tags) {
      this.tagRepository.delete(tag);
    }
  }

  public List<Tag> findAllTags(final UUID repoId, final UUID imageId) {

    return this.tagRepository.findAllByImageRepoIdAndImageId(repoId, imageId);
  }

  private void deleteOldManifests(
      final UUID repoId, final UUID imageId, final Tag tag, final List<Manifest> newManifests) {

    final var oldManifests =
        this.manifestRepository.findAllBindingManifestsAndNonMultiplatform(
            repoId, imageId, tag.getId());

    if (oldManifests.isEmpty()) {
      return;
    }

    final var newDigestSet =
        newManifests.stream().map(Manifest::getDigest).collect(Collectors.toSet());

    final var manifestsToDelete =
        oldManifests.stream()
            .filter(oldManifest -> !newDigestSet.contains(oldManifest.getDigest()))
            .toList();

    for (final var manifest : manifestsToDelete) {
      try {
        manifest.getLayers().clear();
        manifest.setLayers(new HashSet<>());
        this.manifestRepository.delete(manifest);
      } catch (final OptimisticLockException _) {
        log.debug("Manifest already deleted by another thread: {}", manifest.getId());
      }
    }
  }

  public Tag findActiveTagByRepoAndDigest(
      final UUID repoId, final String imageName, final String digest) {

    return this.tagRepository
        .findDistinctFirstByImageRepoIdAndImageNameAndDigestOrderByCreatedAtDesc(
            repoId, imageName, digest)
        .orElseThrow(() -> new ItemNotFoundException("tagNotFound"));
  }

  public Page<ManifestListItem> findManifestsByTagIdContainsName(
      final UUID tagId, final String name, final Pageable pageable) {

    return this.manifestRepository.findByTagIdAndNameContainsName(tagId, name, pageable);
  }

  public Tag findTag(final UUID repoId, final UUID imageId, final String tagName) {

    return this.tagRepository
        .findByImageRepoIdAndImageIdAndName(repoId, imageId, tagName)
        .orElseThrow(() -> new ItemNotFoundException("tagNotFound"));
  }

  public Page<ImageTagListItem> getImageTagsContainsName(
      final UUID repoId, final String imageName, final String tagName, final Pageable pageable) {

    return this.tagRepository.findAllByImageRepoIdAndImageNameContainsName(
        repoId, imageName, tagName, pageable);
  }

  public TagDetail getTagDetail(final UUID repoId, final UUID imageId, final String tagName) {

    final var tag = this.findTag(repoId, imageId, tagName);

    final var tagDetail = TagDetail.of(tag);

    if (!MediaTypes.isIndex(tag.getMediaType())) {
      // Single platform manifest. Must have only one platform and one manifest
      final var platformIterator = tag.getTagPlatforms().iterator();

      if (platformIterator.hasNext()) {
        final var tagPlatform = platformIterator.next();

        final var manifestIterator = tagPlatform.getManifests().iterator();

        if (manifestIterator.hasNext()) {
          final var manifest = manifestIterator.next();

          tagDetail.setConfigDigest(manifest.getConfigDigest());
        }
      }
    }

    return tagDetail;
  }

  private void createManifest(
      final UUID repoId,
      final ImageInfo imageInfo,
      final TagForm tagForm,
      final Set<Layer> layers,
      final Layer configLayer) {

    final var image = this.findImageById(imageInfo.getId());
    final var tag = this.findOrCreateTag(repoId, image, tagForm, 1);

    final var tagPlatform =
        this.findOrCreateTagPlatform(repoId, imageInfo.getId(), tag, tag.getPlatform(), 1);

    final var manifest =
        this.findOrCreateManifest(repoId, imageInfo.getId(), tag.getId(), tagForm, 1);

    this.updateManifestProperties(manifest, layers, configLayer, tagForm, tagPlatform);
  }

  private void createManifestsAndTagPlatforms(
      final UUID repoId,
      final UUID imageId,
      final Tag tag,
      final List<ManifestListManifestInfo> manifestInfo) {

    for (final var newManifest : manifestInfo) {
      this.findOrCreateManifestForMultiplatformRetry(repoId, imageId, tag, newManifest, 1);
    }
  }

  @SneakyThrows
  private void findOrCreateManifestForMultiplatformRetry(
      final UUID repoId,
      final UUID imageId,
      final Tag tag,
      final ManifestListManifestInfo newManifest,
      final int counter) {

    try {
      this.findOrCreateManifestForMultiPlatform(repoId, imageId, tag, newManifest);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }

      Thread.sleep(WAIT_RETRY * counter);
      this.findOrCreateManifestForMultiplatformRetry(
          repoId, imageId, tag, newManifest, counter + 1);
    }
  }

  private void findOrCreateManifestForMultiPlatform(
      final UUID repoId,
      final UUID imageId,
      final Tag tag,
      final ManifestListManifestInfo newManifest) {

    final var manifestOpt =
        this.manifestRepository.findByRepoIdAndImageIdAndTagIdAndDigest(
            repoId, imageId, tag.getId(), newManifest.getDigest());

    if (manifestOpt.isPresent()) {
      return;
    }

    this.createManifestForMultiPlatform(repoId, imageId, tag, newManifest);
  }

  private void createManifestForMultiPlatform(
      final UUID repoId,
      final UUID imageId,
      final Tag tag,
      final ManifestListManifestInfo newManifest) {

    final var tp = this.findOrCreateTagPlatform(repoId, imageId, tag, newManifest.getPlatform(), 1);

    final var manifest = new Manifest();

    final var layers =
        this.layerRepository.findAllByRepoIdAndDigestIn(repoId, newManifest.getLayerDigests());

    final var configLayer =
        this.layerRepository
            .findByRepoIdAndDigest(repoId, newManifest.getConfigDigest())
            .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));

    manifest.setDigest(newManifest.getDigest());
    manifest.setName(newManifest.getDigest());
    manifest.setMediaType(newManifest.getMediaType());
    manifest.setConfigMediaType(newManifest.getConfig().getMediaType());
    manifest.setConfigDigest(newManifest.getConfigDigest());
    manifest.setSchemaVersion((int) newManifest.getSchemaVersion());
    manifest.setLayers(layers);
    manifest.setPlatform(newManifest.getPlatform());
    manifest.setConfigSize(configLayer.getSize());
    manifest.setTagPlatform(tp);

    this.manifestRepository.save(manifest);
  }

  @SneakyThrows
  private Tag findOrCreateTag(
      final UUID repoId, final Image image, final TagForm tagForm, final int counter) {

    try {
      return this.findOrCreateTag(repoId, image, tagForm);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }

      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateTag(repoId, image, tagForm, counter + 1);
    }
  }

  @SneakyThrows
  private TagPlatform findOrCreateTagPlatform(
      final UUID repoId,
      final UUID imageId,
      final Tag tag,
      final String platform,
      final int counter) {

    try {
      return this.findOrCreateTagPlatform(repoId, imageId, tag, platform);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }

      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateTagPlatform(repoId, imageId, tag, platform, counter + 1);
    }
  }

  @SneakyThrows
  private Manifest findOrCreateManifest(
      final UUID repoId,
      final UUID imageId,
      final UUID tagId,
      final TagForm form,
      final int counter) {

    try {
      return this.findOrCreateManifest(repoId, imageId, tagId, form);
    } catch (final DataIntegrityViolationException e) {
      if (counter == RETRY_COUNT) {
        throw e;
      }

      Thread.sleep(WAIT_RETRY * counter);
      return this.findOrCreateManifest(repoId, imageId, tagId, form, counter + 1);
    }
  }

  private Tag findOrCreateTag(final UUID repoId, final Image image, final TagForm tagForm) {

    final var tagOpt =
        this.tagRepository.findByImageRepoIdAndImageIdAndName(
            repoId, image.getId(), tagForm.getTag());

    if (tagOpt.isPresent()) {
      final var existingTag = tagOpt.get();
      existingTag.setDigest(tagForm.getManifestDigest());
      existingTag.setMediaType(tagForm.getCalculatedMediaType());
      existingTag.setPlatform(tagForm.getPlatform());
      return this.tagRepository.save(existingTag);
    }

    return this.createTag(tagForm, image);
  }

  private Tag createTag(final TagForm tagForm, final Image image) {

    final var tag = new Tag();
    tag.setName(tagForm.getTag());
    tag.setPlatform(tagForm.getPlatform());
    tag.setImage(image);
    tag.setDigest(tagForm.getManifestDigest());
    tag.setMediaType(tagForm.getCalculatedMediaType());

    return this.tagRepository.save(tag);
  }

  private TagPlatform findOrCreateTagPlatform(
      final UUID repoId, final UUID imageId, final Tag tag, final String platform) {

    final var tagPlatformOpt =
        this.tagPlatformRepository.findByTagImageRepoIdAndTagImageIdAndTagIdAndPlatform(
            repoId, imageId, tag.getId(), platform);

    return tagPlatformOpt.orElseGet(() -> this.createTagPlatform(tag, platform));
  }

  private TagPlatform createTagPlatform(final Tag tag, final String platform) {

    final var tagPlatform = new TagPlatform();
    tagPlatform.setTag(tag);
    tagPlatform.setPlatform(platform);

    return this.tagPlatformRepository.save(tagPlatform);
  }

  private Manifest findOrCreateManifest(
      final UUID repoId, final UUID imageId, final UUID tagId, final TagForm form) {

    final var manifestOpt =
        this.manifestRepository.findByRepoIdAndImageIdAndTagId(repoId, imageId, tagId);

    return manifestOpt.orElseGet(() -> this.createManifestByTagForm(form));
  }

  private TagDetail mapToTagDetailWithConfigDigest(final Tag tag) {

    String configDigest = null;

    if (!MediaTypes.isIndex(tag.getMediaType())) {
      // Single platform manifest. Must have only one platform and one manifest
      final var platformIterator = tag.getTagPlatforms().iterator();

      if (platformIterator.hasNext()) {
        final var tagPlatform = platformIterator.next();

        final var manifestIterator = tagPlatform.getManifests().iterator();

        if (manifestIterator.hasNext()) {
          final var manifest = manifestIterator.next();

          configDigest = manifest.getConfigDigest();
        }
      }
    }

    return TagDetail.of(tag, configDigest);
  }

  private Image findImageById(final UUID imageId) {

    return this.imageRepository
        .findById(imageId)
        .orElseThrow(() -> new ItemNotFoundException("imageNotFound"));
  }

  private Set<Layer> findLayersByRepoIdAndForm(final TagForm tagForm, final UUID repoId) {

    final var layerDigests = tagForm.getManifestInfo().getLayerDigests();

    return this.layerRepository.findAllByRepoIdAndDigestIn(repoId, layerDigests);
  }

  private Layer findConfigLayerByRepoIdAndDigest(final TagForm tagForm, final UUID repoId) {

    final var configDigest = tagForm.getManifestInfo().getConfigDigest();

    return this.layerRepository
        .findByRepoIdAndDigest(repoId, configDigest)
        .orElseThrow(() -> new ItemNotFoundException("itemNotFound"));
  }

  private Manifest createManifestByTagForm(final TagForm form) {

    final var manifestInfo = form.getManifestInfo();

    final var manifest = new Manifest();

    manifest.setDigest(form.getManifestDigest());
    manifest.setName(form.getTag());
    manifest.setMediaType(manifestInfo.getMediaType());
    manifest.setConfigMediaType(manifestInfo.getConfig().getMediaType());
    manifest.setConfigDigest(manifestInfo.getConfig().getDigest());
    manifest.setSchemaVersion((int) manifestInfo.getSchemaVersion());
    manifest.setLayers(new HashSet<>());
    manifest.setPlatform(form.getPlatform());

    return this.manifestRepository.save(manifest);
  }

  private void createManifestList(
      final UUID repoId,
      final UUID imageId,
      final TagForm tagForm,
      final TagPlatform manifestListTagPlatform) {

    final var manifestListOpt =
        this.manifestRepository.findByRepoIdAndImageIdAndTagIdAndPlatform(
            repoId, imageId, tagForm.getTag(), MULTIPLATFORM);

    if (manifestListOpt.isPresent()) {
      this.manifestRepository.delete(manifestListOpt.get());
      this.manifestRepository.flush();
    }

    this.createManifestListByTagForm(tagForm, manifestListTagPlatform);
  }

  private void createManifestListByTagForm(
      final TagForm tagForm, final TagPlatform manifestListTagPlatform) {

    final var manifestList = new Manifest();

    manifestList.setName(tagForm.getTag());
    manifestList.setLayers(new HashSet<>());
    manifestList.setPlatform(tagForm.getPlatform());
    manifestList.setDigest(tagForm.getManifestDigest());
    manifestList.setMediaType(tagForm.getManifestList().getMediaType());
    manifestList.setSchemaVersion(tagForm.getManifestList().getSchemaVersion());
    manifestList.setLastUpdatedAt(Instant.now());
    manifestList.setTagPlatform(manifestListTagPlatform);

    this.manifestRepository.save(manifestList);
  }

  private void updateManifestProperties(
      final Manifest manifest,
      final Set<Layer> layers,
      final Layer configLayer,
      final TagForm tagForm,
      final TagPlatform tagPlatform) {

    manifest.getLayers().clear();
    manifest.getLayers().addAll(layers);
    manifest.getLayers().add(configLayer);
    manifest.setLastUpdatedAt(Instant.now());
    manifest.setDigest(tagForm.getManifestDigest());
    manifest.setMediaType(tagForm.getManifestInfo().getMediaType());
    manifest.setPlatform(tagForm.getPlatform());
    manifest.setConfigSize(configLayer.getSize());
    manifest.setConfigDigest(tagForm.getManifestInfo().getConfig().getDigest());
    manifest.setConfigMediaType(tagForm.getManifestInfo().getConfig().getMediaType());
    manifest.setLayers(layers);
    manifest.setTagPlatform(tagPlatform);

    this.manifestRepository.save(manifest);
  }
}
