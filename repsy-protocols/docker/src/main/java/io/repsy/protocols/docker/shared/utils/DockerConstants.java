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
package io.repsy.protocols.docker.shared.utils;

import org.jspecify.annotations.NullMarked;

@NullMarked
public final class DockerConstants {

  private DockerConstants() {}

  public static final String IMAGE_NAME_REGEX = "(?<imageName>[a-zA-Z0-9_\\-]+)";
  public static final String SHA_REGEX = "(?<sha>sha256:[0-9a-fA-F]{64})$";
  public static final String BLOBS = "blobs";
  public static final String SHA256_PREFIX = "sha256:";
  public static final String MANIFESTS = "manifests";
  public static final String BASIC_PREFIX = "Basic ";
}
