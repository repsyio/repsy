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
package io.repsy.protocols.pypi.shared.python_package.dtos;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@SuppressWarnings("MemberName")
public class PackageUploadForm {
  // fields provided by twine
  private String name;
  private String version;
  private String filetype;
  private String pyversion;
  private String metadata_version;
  private String summary;
  private String home_page;
  private String author;
  private String author_email;
  private String maintainer;
  private String maintainer_email;
  private String license;
  private String description;
  private String[] keywords;
  private String[] platform;
  private String[] classifiers;
  private String download_url;
  private String[] supported_platform;
  private String comment;
  private String md5_digest;
  private String sha256_digest;
  private String blake2_256_digest;
  private String provides;
  private String requires;
  private String obsoletes;
  private String[] project_urls;
  private String[] provides_dist;
  private String[] obsoletes_dist;
  private String[] requires_dist;
  private String[] requires_external;
  private String requires_python;
  private String[] provides_extras;
  private String description_content_type;
  private String protocol_version;

  // fields used by repsy
  @JsonIgnore private String normalizedName;
}
