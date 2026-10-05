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
package io.repsy.os.server.protocols.docker.shared.cleanup.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.os.generated.model.CleanupPolicyForm;
import io.repsy.os.generated.model.CleanupPolicyItem;
import io.repsy.os.server.protocols.docker.shared.cleanup.entities.CleanupCadence;
import io.repsy.os.server.protocols.docker.shared.cleanup.entities.CleanupPolicy;
import io.repsy.os.server.protocols.docker.shared.cleanup.repositories.CleanupPolicyRepository;
import io.repsy.os.server.protocols.docker.shared.image.repositories.ImageRepository;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.os.server.protocols.docker.shared.tag.repositories.TagRepository;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.repo.mappers.RepoConverter;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rules of the Docker cleanup policy and the selection of the tags it deletes. */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@NullMarked
public class CleanupPolicyService {

  private static final int DEFAULT_KEEP_LAST_N = 10;
  private static final int DEFAULT_KEEP_DAYS = 7;

  private final CleanupPolicyRepository policyRepository;
  private final TagRepository tagRepository;
  private final ImageRepository imageRepository;
  private final RepoRepository repoRepository;
  private final RepoConverter repoConverter;

  /** Reads the policy; a disabled default one is created the first time. */
  @Transactional
  public CleanupPolicyItem getCleanupPolicy(final UUID repoId) {

    return toItem(
        this.policyRepository
            .findByRepoId(repoId)
            .orElseGet(() -> this.createDefaultPolicy(repoId)));
  }

  @Transactional
  public CleanupPolicyItem updatePolicy(final UUID repoId, final CleanupPolicyForm form) {

    final var policy =
        this.policyRepository
            .findByRepoId(repoId)
            .orElseThrow(() -> new ItemNotFoundException("cleanupPolicyNotFound"));

    if (!policy.isEnabled()) {
      throw new BadRequestException("cleanupPolicyDisabled");
    }

    // Refused now, not when the policy runs: a bad pattern would otherwise fail every run.
    validateRegex(form.getNameRegex());
    validateRegex(form.getNameRegexKeep());

    policy.setNameRegex(form.getNameRegex());
    policy.setNameRegexKeep(form.getNameRegexKeep());
    policy.setKeepLastN(form.getKeepLastN());
    policy.setKeepDays(form.getKeepDays());
    policy.setCadence(CleanupCadence.valueOf(form.getCadence().name()));

    return toItem(this.policyRepository.save(policy));
  }

  @Transactional
  public CleanupPolicyItem enableOrDisablePolicy(final UUID repoId, final boolean enabled) {

    final var policy =
        this.policyRepository
            .findByRepoId(repoId)
            .orElseGet(() -> this.createDefaultPolicy(repoId));

    policy.setEnabled(enabled);
    policy.setNextRunAt(enabled ? Instant.now() : null);

    return toItem(this.policyRepository.save(policy));
  }

  /** Marks the policy due now; the scheduler picks it up on its next pass. */
  @Transactional
  public void requestRun(final UUID repoId) {

    final var policy =
        this.policyRepository
            .findByRepoId(repoId)
            .orElseGet(() -> this.createDefaultPolicy(repoId));

    if (!policy.isEnabled()) {
      throw new BadRequestException("cleanupPolicyDisabled");
    }

    policy.setNextRunAt(Instant.now());
    this.policyRepository.save(policy);
  }

  /**
   * Selects the tags of every policy that is due and moves each policy's window on, in one
   * transaction. The tags are deleted by the caller afterwards.
   */
  @Transactional
  public List<TagDeletionInfo> processCleanup() {

    final var now = Instant.now();
    final var policies = this.policyRepository.findDueForExecution(now);
    log.info("Found {} cleanup policies due for execution", policies.size());

    final var allDeletions = new ArrayList<TagDeletionInfo>();

    for (final var policy : policies) {
      try {
        final var repoInfo = this.repoConverter.toRepoInfo(policy.getRepo());
        allDeletions.addAll(this.cleanup(policy, now, repoInfo));
      } catch (final RuntimeException e) {
        log.error("Failed to process the cleanup policy of repository {}", policy.getId(), e);
      }
    }

    return allDeletions;
  }

  private CleanupPolicy createDefaultPolicy(final UUID repoId) {

    final var repo =
        this.repoRepository
            .findById(repoId)
            .orElseThrow(() -> new ItemNotFoundException("repoNotFound"));

    final var policy = new CleanupPolicy();

    policy.setEnabled(false);
    policy.setNameRegex(".*");
    policy.setCadence(CleanupCadence.WEEKLY);
    policy.setKeepDays(DEFAULT_KEEP_DAYS);
    policy.setKeepLastN(DEFAULT_KEEP_LAST_N);
    policy.setRepo(repo);

    return this.policyRepository.save(policy);
  }

  private List<TagDeletionInfo> cleanup(
      final CleanupPolicy policy, final Instant now, final RepoInfo repoInfo) {

    final var rules = Rules.of(policy);
    final var deletions = new ArrayList<TagDeletionInfo>();

    for (final var image : this.imageRepository.findAllByRepoId(policy.getId())) {
      if (image.getName() != null) {
        final var allTags =
            this.tagRepository.findAllByImageRepoIdAndImageId(policy.getId(), image.getId());

        this.selectTags(allTags, rules, now).stream()
            .map(tag -> new TagDeletionInfo(repoInfo, image.getName(), tag, rules.keepDays()))
            .forEach(deletions::add);
      }
    }

    policy.setLastRunAt(now);
    policy.setNextRunAt(now.plus(policy.getCadence().getDuration()));
    this.policyRepository.save(policy);

    return deletions;
  }

  /** The names of the tags of one image the rules delete. */
  private List<String> selectTags(final List<Tag> allTags, final Rules rules, final Instant now) {

    final var old = this.filterByRules(allTags, rules.name(), rules.keepDays(), now);
    final var newest = newestTagNames(allTags, rules.keepLastN());

    return old.stream()
        .map(Tag::getName)
        .filter(name -> rules.keep() == null || !rules.keep().matcher(name).matches())
        .filter(name -> !"latest".equals(name))
        .filter(name -> !newest.contains(name))
        .toList();
  }

  private record Rules(Pattern name, @Nullable Pattern keep, int keepLastN, int keepDays) {

    static Rules of(final CleanupPolicy policy) {

      return new Rules(
          compileAnchored(StringUtils.defaultIfBlank(policy.getNameRegex(), ".*")),
          StringUtils.isBlank(policy.getNameRegexKeep())
              ? null
              : compileAnchored(policy.getNameRegexKeep()),
          policy.getKeepLastN(),
          policy.getKeepDays());
    }
  }

  private List<Tag> filterByRules(
      final List<Tag> tags, final Pattern namePattern, final int keepDays, final Instant now) {

    final var threshold = now.minus(Duration.ofDays(keepDays));

    return tags.stream()
        .filter(t -> namePattern.matcher(t.getName()).matches())
        .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isBefore(threshold))
        .toList();
  }

  private static List<String> newestTagNames(final List<Tag> tags, final int keepN) {

    if (keepN <= 0) {
      return Collections.emptyList();
    }

    return tags.stream()
        .filter(t -> t.getCreatedAt() != null)
        .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
        .limit(keepN)
        .map(Tag::getName)
        .toList();
  }

  private static Pattern compileAnchored(final String regex) {
    return Pattern.compile("\\A(?:" + regex + ")\\z");
  }

  private static void validateRegex(final @Nullable String regex) {

    if (StringUtils.isBlank(regex)) {
      return;
    }

    try {
      compileAnchored(regex);
    } catch (final PatternSyntaxException e) {
      throw new BadRequestException("invalidRegex");
    }
  }

  private static CleanupPolicyItem toItem(final CleanupPolicy policy) {

    return new CleanupPolicyItem()
        .enabled(policy.isEnabled())
        .cadence(CleanupPolicyItem.CadenceEnum.valueOf(policy.getCadence().name()))
        .keepLastN(policy.getKeepLastN())
        .keepDays(policy.getKeepDays())
        .nameRegex(policy.getNameRegex())
        .nameRegexKeep(policy.getNameRegexKeep())
        .lastRunAt(policy.getLastRunAt())
        .nextRunAt(policy.getNextRunAt());
  }
}
