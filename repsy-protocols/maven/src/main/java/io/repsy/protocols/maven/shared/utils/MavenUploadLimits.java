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
package io.repsy.protocols.maven.shared.utils;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

/**
 * Caps how much of an upload the Maven protocol facade buffers in memory (or spools to disk) before
 * storing it, so a client cannot exhaust the instance's heap or disk with an oversized {@code
 * maven-metadata.xml}, POM or signature. A real one is at most a few kilobytes to a few megabytes;
 * these limits leave generous headroom above that (RPS-1121).
 */
@UtilityClass
@NullMarked
public class MavenUploadLimits {

  private static final long MEBIBYTE = 1024L * 1024L;
  private static final long KIBIBYTE = 1024L;

  /** The largest {@code maven-metadata.xml} (or a checksum/signature of one) accepted. */
  public static final long MAX_METADATA_BYTES = 10 * MEBIBYTE;

  /** The largest {@code .pom} accepted. */
  public static final long MAX_POM_BYTES = 10 * MEBIBYTE;

  /** The largest {@code .asc} signature of an artifact file accepted. */
  public static final long MAX_SIGNATURE_BYTES = 64 * KIBIBYTE;
}
