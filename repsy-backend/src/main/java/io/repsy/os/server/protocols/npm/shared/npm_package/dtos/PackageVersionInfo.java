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
package io.repsy.os.server.protocols.npm.shared.npm_package.dtos;

import io.repsy.protocols.npm.shared.npm_package.dtos.BasePackageVersionInfo;
import java.time.Instant;
import java.util.UUID;
import lombok.experimental.SuperBuilder;

@SuperBuilder
public class PackageVersionInfo extends BasePackageVersionInfo<UUID> {
  private String packageScope;
  private String packageName;
  private String version;
  private String authorName;
  private String authorEmail;
  private String authorUrl;
  private String bugsUrl;
  private String bugsEmail;
  private String description;
  private String homepage;
  private String license;
  private String repositoryType;
  private String repositoryUrl;
  private boolean deprecated;
  private String deprecationMessage;
  private boolean deleted;
  private Instant createdAt;
}
