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
import io.repsy.os.generated.model.ManifestListItem;
import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.layer.entities.Layer;
import io.repsy.os.server.protocols.docker.shared.layer.repositories.LayerRepository;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.TagDetail;
import io.repsy.os.server.protocols.docker.shared.tag.dtos.manifest.ManifestDetail;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.entities.ManifestChild;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.mappers.ManifestConverter;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestChildRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.ManifestRepository;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.protocols.docker.shared.image.dtos.BaseImageInfo;
import io.repsy.protocols.docker.shared.image.exceptions.ImageDeletedException;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import io.repsy.protocols.docker.shared.tag.dtos.ManifestListManifest;
import io.repsy.protocols.docker.shared.tag.dtos.TagForm;
import io.repsy.protocols.docker.shared.tag.services.ManifestService;
import io.repsy.protocols.docker.shared.utils.DockerConstants;
import io.repsy.protocols.docker.shared.utils.DockerDigestCalculator;
import io.repsy.protocols.docker.shared.utils.ManifestNameGenerator;
import io.repsy.protocols.docker.shared.utils.MediaTypes;
import io.repsy.protocols.shared.utils.BlobDigests;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The manifests and tags of a Docker repo (RPS-1216). A manifest is content-addressed: one row per
 * image and {@code sha256} digest, created by the first push of its bytes and kept when a tag moves
 * away from it, so it stays pullable by its digest. A tag is a pointer to a manifest; an index
 * references the manifests it lists through {@link ManifestChild} edges.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class ManifestTxService implements ManifestService<UUID> {

  private static final String MULTIPLATFORM = "Multiplatform";

  private static final Comparator<ManifestRow> BY_ID = Comparator.comparing(ManifestRow::id);

  private static final Comparator<ManifestRow> BY_NAME =
      Comparator.comparing(row -> row.item().getName());

  private static final Comparator<ManifestRow> BY_CREATED_AT =
      Comparator.comparing(
          row -> row.item().getCreatedAt(), Comparator.nullsFirst(Comparator.naturalOrder()));

  private static final Map<String, Comparator<ManifestRow>> SORTS =
      Map.of("id", BY_ID, "name", BY_NAME, "createdAt", BY_CREATED_AT);

  private final ManifestConverter manifestConverter;
  private final ImageRepository imageRepository;
  private final LayerRepository layerRepository;
  private final ManifestRepository manifestRepository;
  private final ManifestChildRepository manifestChildRepository;
  private final TagRepository tagRepository;

  /** A row of the panel's list of a tag's manifests: the item, and the id its sort key uses. */
  private record ManifestRow(UUID id, ManifestListItem item) {}

  @Override
  public void verifyManifestsExist(
      final UUID repoId, final UUID imageId, final List<String> digests) {

    for (final var digest : digests) {
      this.findManifest(imageId, digest);
    }
  }

  @Override
  @Transactional
  public void createManifestList(final UUID repoId, final UUID imageId, final TagForm tagForm) {

    final var image = this.lockImageForPush(imageId);
    final var manifest = this.findOrCreateIndex(image, tagForm);

    this.replaceChildren(manifest, image, tagForm.getManifestList().getManifests());
    this.pointTag(image, tagForm, manifest);
  }

  @Override
  @Transactional
  public void createSinglePlatformManifest(
      final UUID repoId, final BaseImageInfo<UUID> baseImageInfo, final TagForm tagForm) {

    final var imageInfo = (ImageInfo) baseImageInfo;

    final var image = this.lockImageForPush(imageInfo.getId());
    final var layers = this.findLayersByRepoIdAndForm(tagForm, repoId);
    final var configLayer = this.findConfigLayerByRepoIdAndDigest(tagForm, repoId);

    final var manifest = this.findOrCreateManifest(image, tagForm, layers, configLayer);

    this.pointTag(image, tagForm, manifest);
  }

  @Override
  public Optional<BaseTagDetail<UUID>> findActiveTagByNameAndRepoAndImage(
      final UUID repoId, final String imageName, final String tag) {

    return this.tagRepository
        .findByImageRepoIdAndImageNameAndName(repoId, imageName, tag)
        .map(this::mapToTagDetailWithConfigDigest);
  }

  @Override
  public ManifestDetail findManifestByRepoIdAndImageNameAndDigest(
      final UUID repoId, final BaseImageInfo<UUID> imageInfo, final String digest) {

    return this.manifestConverter.toManifestDetail(this.findManifest(imageInfo.getId(), digest));
  }

  /**
   * Removes the tag and nothing else: the manifest it pointed at stays stored, untagged and
   * pullable by its digest, until it is deleted explicitly.
   */
  @Transactional
  public void deleteTag(final Tag tag) {

    this.tagRepository.delete(tag);
  }

  public List<Tag> findAllTags(final UUID repoId, final UUID imageId) {

    return this.tagRepository.findAllByImageRepoIdAndImageId(repoId, imageId);
  }

  public boolean existsByImageIdAndConfigDigest(final UUID imageId, final String configDigest) {

    return this.manifestRepository.existsByImageIdAndConfigDigest(imageId, configDigest);
  }

  /**
   * Resolves a tag name or a digest (of either algorithm) to the names the manifest file may have,
   * in the order to try them: the legacy name an earlier version stored it under while the repair
   * service has not renamed it yet, then its digest.
   *
   * @throws ItemNotFoundException {@code imageNotFound} for an image the repo does not have, {@code
   *     manifestNotFound} for a digest the image does not store, {@code tagNotFound} for a tag it
   *     does not have (RPS-1579)
   */
  public List<String> findManifestFileNamesByReference(
      final UUID repoId, final String imageName, final String reference) {

    final var image =
        this.imageRepository
            .findByRepoIdAndName(repoId, imageName)
            .orElseThrow(() -> new ItemNotFoundException("imageNotFound"));

    if (BlobDigests.startsWithDigestPrefix(reference)) {
      return this.manifestRepository
          .findByImageIdAndAnyDigest(image.getId(), DockerDigestCalculator.normalize(reference))
          .map(found -> this.fileNamesOf(repoId, imageName, found))
          .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));
    }

    return this.tagRepository
        .findByImageRepoIdAndImageNameAndName(repoId, imageName, reference)
        .map(Tag::getManifest)
        .map(found -> this.fileNamesOf(repoId, imageName, found))
        .orElseThrow(() -> new ItemNotFoundException("tagNotFound"));
  }

  /**
   * The manifests a tag shows in the panel: the manifest it points at, named after the tag, and,
   * for an index, the manifests the index references, named after their digests.
   */
  public Page<ManifestListItem> findManifestsByTagContainsName(
      final Tag tag, final String name, final Pageable pageable) {

    final var root = tag.getManifest();
    final var rows = new ArrayList<ManifestRow>();
    rows.add(this.toRow(root, tag.getName(), root.getPlatform()));

    for (final var edge : this.manifestChildRepository.findAllByParentId(root.getId())) {
      rows.add(this.toRow(edge.getChild(), edge.getChild().getDigest(), edge.getPlatform()));
    }

    final var matching =
        rows.stream()
            .filter(row -> row.item().getName().contains(name))
            .sorted(this.comparatorOf(pageable.getSort()))
            .map(ManifestRow::item)
            .toList();

    return this.pageOf(matching, pageable);
  }

  public Tag findTag(final UUID repoId, final UUID imageId, final String tagName) {

    return this.tagRepository
        .findByImageRepoIdAndImageIdAndName(repoId, imageId, tagName)
        .orElseThrow(() -> new ItemNotFoundException("tagNotFound"));
  }

  public Page<io.repsy.os.generated.model.ImageTagListItem> getImageTagsContainsName(
      final UUID repoId, final String imageName, final String tagName, final Pageable pageable) {

    return this.tagRepository
        .findAllByImageRepoIdAndImageNameContainsName(repoId, imageName, tagName, pageable)
        .map(this.manifestConverter::toTagDto);
  }

  public io.repsy.os.generated.model.TagDetail getTagDetail(
      final UUID repoId, final UUID imageId, final String tagName) {

    return this.manifestConverter.toTagDetail(this.findTag(repoId, imageId, tagName));
  }

  private List<String> fileNamesOf(
      final UUID repoId, final String imageName, final Manifest manifest) {

    final var names = new ArrayList<String>();

    if (manifest.getStorageName() != null) {
      names.add(ManifestNameGenerator.generate(repoId, imageName, manifest.getStorageName()));
    }

    names.add(manifest.getDigest());

    return names;
  }

  private ManifestRow toRow(final Manifest manifest, final String name, final String platform) {

    final var item =
        new ManifestListItem()
            .name(name)
            .digest(manifest.getDigest())
            .createdAt(manifest.getCreatedAt())
            .platform(platform)
            .configDigest(manifest.getConfigDigest());

    return new ManifestRow(manifest.getId(), item);
  }

  private Comparator<ManifestRow> comparatorOf(final Sort sort) {

    return sort.stream().map(this::comparatorOf).reduce(Comparator::thenComparing).orElse(BY_ID);
  }

  private Comparator<ManifestRow> comparatorOf(final Sort.Order order) {

    final var comparator = SORTS.get(order.getProperty());

    if (comparator == null) {
      throw new IllegalArgumentException("Unsupported sort property " + order.getProperty());
    }

    return order.isAscending() ? comparator : comparator.reversed();
  }

  private <T> Page<T> pageOf(final List<T> all, final Pageable pageable) {

    if (pageable.isUnpaged()) {
      return new PageImpl<>(all, pageable, all.size());
    }

    final var from = (int) Math.min(pageable.getOffset(), all.size());
    final var to = Math.min(from + pageable.getPageSize(), all.size());

    return new PageImpl<>(all.subList(from, to), pageable, all.size());
  }

  private Manifest findManifest(final UUID imageId, final String digest) {

    return this.manifestRepository
        .findByImageIdAndAnyDigest(imageId, DockerDigestCalculator.normalize(digest))
        .orElseThrow(() -> new ItemNotFoundException("manifestNotFound"));
  }

  /**
   * Makes the index reference exactly the manifests it lists now. The manifests themselves are
   * never touched: the ones an earlier push of the tag referenced and this one does not stay
   * stored, untagged.
   */
  private void replaceChildren(
      final Manifest parent, final Image image, final List<ManifestListManifest> entries) {

    final var wanted = new LinkedHashMap<UUID, ManifestChild>();

    for (final var entry : entries) {
      final var child = this.findManifest(image.getId(), entry.getDigest());

      wanted.putIfAbsent(child.getId(), new ManifestChild(parent, child, platformOf(entry)));
    }

    final var existing = this.manifestChildRepository.findAllByParentId(parent.getId());

    this.manifestChildRepository.deleteAll(
        existing.stream().filter(edge -> !wanted.containsKey(edge.getChild().getId())).toList());

    final var kept =
        existing.stream().map(edge -> edge.getChild().getId()).collect(Collectors.toSet());

    wanted.entrySet().stream()
        .filter(entry -> !kept.contains(entry.getKey()))
        .forEach(entry -> this.manifestChildRepository.save(entry.getValue()));
  }

  /**
   * An index entry without a platform is legitimate (RPS-1117: an index grouping an artifact and
   * its referrers, not per-platform images).
   */
  private static String platformOf(final ManifestListManifest entry) {

    return entry.getPlatform() != null
        ? entry.getPlatform().toString()
        : DockerConstants.UNKNOWN_PLATFORM;
  }

  /**
   * Creates the tag, or moves it to the manifest. Only the pointer moves: the manifest the tag
   * pointed at before is left as it is.
   */
  private void pointTag(final Image image, final TagForm tagForm, final Manifest manifest) {

    if (!tagForm.isTagReference()) {
      return;
    }

    final var tag =
        this.tagRepository
            .findByImageIdAndName(image.getId(), tagForm.getTag())
            .orElseGet(() -> this.newTag(image, tagForm));

    tag.setManifest(manifest);
    tag.setDigest(manifest.getDigest());
    tag.setMediaType(tagForm.getCalculatedMediaType());
    tag.setPlatform(tagForm.getPlatform());

    this.tagRepository.save(tag);
  }

  private Tag newTag(final Image image, final TagForm tagForm) {

    final var tag = new Tag();
    tag.setName(tagForm.getTag());
    tag.setImage(image);

    return tag;
  }

  private TagDetail mapToTagDetailWithConfigDigest(final Tag tag) {

    final var configDigest =
        MediaTypes.isIndex(tag.getMediaType()) ? null : tag.getManifest().getConfigDigest();

    return TagDetail.of(tag, configDigest);
  }

  /**
   * The image row, share-locked until the push's transaction ends: a delete of the image's last
   * manifest waits for it, and an image that is gone already (it went with its last manifest after
   * the push looked it up) is reported so the handler can create it again.
   */
  private Image lockImageForPush(final UUID imageId) {

    return this.imageRepository
        .findByIdForShare(imageId)
        .orElseThrow(() -> new ImageDeletedException(imageId));
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

  private Manifest findOrCreateManifest(
      final Image image, final TagForm form, final Set<Layer> layers, final Layer configLayer) {

    return this.manifestRepository
        .findByImageIdAndDigest(image.getId(), form.getManifestDigest())
        .map(existing -> this.completeDigests(existing, form))
        .orElseGet(() -> this.createManifest(image, form, layers, configLayer));
  }

  private Manifest createManifest(
      final Image image, final TagForm form, final Set<Layer> layers, final Layer configLayer) {

    final var manifestInfo = form.getManifestInfo();
    final var allLayers = new HashSet<>(layers);
    allLayers.add(configLayer);

    final var manifest = this.newManifest(image, form);
    manifest.setMediaType(manifestInfo.getMediaType());
    manifest.setConfigMediaType(manifestInfo.getConfig().getMediaType());
    manifest.setConfigDigest(manifestInfo.getConfig().getDigest());
    manifest.setConfigSize(configLayer.getSize());
    manifest.setSchemaVersion(Math.toIntExact(manifestInfo.getSchemaVersion()));
    manifest.setLayers(allLayers);
    manifest.setPlatform(form.getPlatform());

    return this.manifestRepository.save(manifest);
  }

  private Manifest findOrCreateIndex(final Image image, final TagForm form) {

    return this.manifestRepository
        .findByImageIdAndDigest(image.getId(), form.getManifestDigest())
        .map(existing -> this.completeDigests(existing, form))
        .orElseGet(() -> this.createIndex(image, form));
  }

  private Manifest createIndex(final Image image, final TagForm form) {

    final var manifest = this.newManifest(image, form);
    manifest.setMediaType(form.getManifestList().getMediaType());
    manifest.setSchemaVersion(form.getManifestList().getSchemaVersion());
    manifest.setLayers(new HashSet<>());
    manifest.setPlatform(MULTIPLATFORM);

    return this.manifestRepository.save(manifest);
  }

  private Manifest newManifest(final Image image, final TagForm form) {

    final var manifest = new Manifest();
    manifest.setImage(image);
    manifest.setDigest(form.getManifestDigest());
    manifest.setDigestSha512(form.getManifestDigestSha512());

    return manifest;
  }

  /** A row written before RPS-1216 has no {@code sha512} digest; the push brings the bytes. */
  private Manifest completeDigests(final Manifest manifest, final TagForm form) {

    if (manifest.getDigestSha512() == null) {
      manifest.setDigestSha512(form.getManifestDigestSha512());
    }

    return manifest;
  }
}
