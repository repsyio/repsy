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
package io.repsy.os.nullability.fixtures;

import io.repsy.os.server.protocols.maven.shared.artifact.entities.Artifact;
import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Planted projections for {@code ProjectionNullabilityRules}: the getters named in the {@code Bad}
 * interfaces must be found, and the {@code Ok} ones must not (RPS-2077). They are declared on the
 * real {@link Artifact} entity, whose {@code latest} column is nullable and {@code groupName} is
 * NOT NULL.
 */
public final class ProjectionViolations {

  private ProjectionViolations() {}

  /** A nullable column read as a plain property. */
  public interface BadColumn {
    String getLatest();
  }

  /** A column read through a left join. */
  public interface BadLeftJoin {
    String getVersionName();
  }

  /** An aggregate, which the rule cannot prove non-null. */
  public interface BadAggregate {
    String getNewest();
  }

  /** The nullable column of an entity that is selected whole. */
  public interface BadWholeEntity {
    String getLatest();
  }

  /** A NOT NULL column. */
  public interface OkColumn {
    String getGroupName();
  }

  /** A nullable column that says so. */
  public interface OkAnnotated {
    @Nullable String getLatest();
  }

  /** A count, which is never null. */
  public interface OkCount {
    Long getVersions();
  }

  /** The planted queries. */
  public interface Repo extends JpaRepository<Artifact, UUID> {

    @Query("select a.latest as latest from Artifact a")
    Collection<BadColumn> column();

    @Query("select v.versionName as versionName from Artifact a left join a.artifactVersions v")
    Collection<BadLeftJoin> leftJoin();

    @Query("select max(a.artifactName) as newest from Artifact a")
    Collection<BadAggregate> aggregate();

    @Query("select a from Artifact a")
    Collection<BadWholeEntity> wholeEntity();

    @Query("select a.groupName as groupName from Artifact a")
    Collection<OkColumn> okColumn();

    @Query("select a.latest as latest from Artifact a")
    Collection<OkAnnotated> okAnnotated();

    @Query("select count(v) as versions from Artifact a join a.artifactVersions v")
    Collection<OkCount> okCount();
  }
}
