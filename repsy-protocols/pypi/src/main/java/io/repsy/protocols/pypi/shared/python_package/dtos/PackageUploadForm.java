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
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

// The no-args constructor (Spring binding of the multipart form) leaves the fields to be set
// afterwards; the required ones are validated by the handler before they are read.
@Data
@NoArgsConstructor
@SuppressWarnings("NullAway.Init")
public class PackageUploadForm {
  // fields provided by twine
  private String name;
  private String version;
  private String filetype;
  private String pyversion;

  @JsonProperty("metadata_version")
  private String metadataVersion;

  private String summary;

  @Nullable
  @JsonProperty("home_page")
  private String homePage;

  @Nullable private String author;

  @Nullable
  @JsonProperty("author_email")
  private String authorEmail;

  private String maintainer;

  @JsonProperty("maintainer_email")
  private String maintainerEmail;

  @Nullable private String license;
  private String description;
  private String[] keywords;
  private String[] platform;
  private String[] classifiers;

  @JsonProperty("download_url")
  private String downloadUrl;

  @JsonProperty("supported_platform")
  private String[] supportedPlatform;

  private String comment;

  @JsonProperty("md5_digest")
  private String md5Digest;

  @JsonProperty("sha256_digest")
  private String sha256Digest;

  @JsonProperty("blake2_256_digest")
  private String blake2256Digest;

  private String provides;
  private String requires;
  private String obsoletes;

  @JsonProperty("project_urls")
  private String[] projectUrls;

  @JsonProperty("provides_dist")
  private String[] providesDist;

  @JsonProperty("obsoletes_dist")
  private String[] obsoletesDist;

  @JsonProperty("requires_dist")
  private String[] requiresDist;

  @JsonProperty("requires_external")
  private String[] requiresExternal;

  @JsonProperty("requires_python")
  private String requiresPython;

  @JsonProperty("provides_extras")
  private String[] providesExtras;

  @Nullable
  @JsonProperty("description_content_type")
  private String descriptionContentType;

  @JsonProperty("protocol_version")
  private String protocolVersion;

  // fields used by repsy
  @JsonIgnore private String normalizedName;
}
