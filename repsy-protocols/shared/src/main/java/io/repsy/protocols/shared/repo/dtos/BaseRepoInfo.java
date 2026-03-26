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
package io.repsy.protocols.shared.repo.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseRepoInfo<ID> {
  private @JsonIgnore ID id;
  private UUID storageKey;
  private @NonNull String name;
  private @Nullable String description;
  private long diskUsage;
  private boolean privateRepo;
  private @Nullable Boolean snapshots;
  private @Nullable Boolean releases;
  private boolean allowOverride;
  private boolean searchable;
  private RepoType type;
}
