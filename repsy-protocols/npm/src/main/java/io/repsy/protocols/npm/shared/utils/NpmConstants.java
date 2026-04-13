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
package io.repsy.protocols.npm.shared.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullMarked;

@NullMarked
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NpmConstants {

  public static final String DIST_TAGS = "dist-tags";
  public static final String VERSIONS = "versions";
  public static final String BUGS = "bugs";
  public static final String MODIFIED = "modified";
  public static final String HAS_SHRINKWRAP = "_hasShrinkwrap";
  public static final String ATTACHMENTS = "_attachments";
  public static final String MAINTAINERS = "maintainers";
  public static final String CONTRIBUTORS = "contributors";
  public static final String AUTHOR = "author";
  public static final String REPOSITORY = "repository";
  public static final String DEPRECATED = "deprecated";
  public static final String EMAIL = "email";
  public static final String LATEST = "latest";
  public static final String NAME = "name";
  public static final String URL = "url";
  public static final String METADATA_FILENAME = "package.json";
  public static final String SCOPE_NAME = "scopeName";
  public static final String PACKAGE_NAME = "packageName";
  public static final String TAG_NAME = "tagName";
  public static final String VERSION_NAME = "versionName";
  public static final String FILE_NAME = "fileName";
  public static final String DIST = "dist";
  public static final String TARBALL = "tarball";
  public static final String PAYLOAD = "payload";
  public static final String METADATA = "metadata";
}
