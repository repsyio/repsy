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
package io.repsy.protocols.cargo.shared.crate.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseCrateVersionInfo<ID> {

  private ID crateId;

  private String name;

  private String version;

  @Nullable private String readme;

  @Nullable private String license;

  @JsonProperty("license_file")
  @Nullable
  private String licenseFile;

  @Nullable private String documentation;

  @Nullable private String edition;

  @JsonProperty("rust_version")
  @Nullable
  private String rustVersion;

  private List<CrateIndexDep> deps;

  private long downloads;

  @JsonProperty("created_at")
  private Instant createdAt;
}
