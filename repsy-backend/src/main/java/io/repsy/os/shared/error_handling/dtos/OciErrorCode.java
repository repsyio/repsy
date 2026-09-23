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
package io.repsy.os.shared.error_handling.dtos;

/**
 * The error codes of the OCI distribution specification that the {@code /v2/} endpoints answer
 * with, as they appear in {@code errors[].code}.
 */
public enum OciErrorCode {
  /** A blob is not known to the registry. */
  BLOB_UNKNOWN,
  /** The blob upload session is not known to the registry. */
  BLOB_UPLOAD_UNKNOWN,
  /** A provided digest did not match the content, or is not a valid digest. */
  DIGEST_INVALID,
  /** A manifest references a blob the registry does not have. */
  MANIFEST_BLOB_UNKNOWN,
  /** The manifest is malformed. */
  MANIFEST_INVALID,
  /** The manifest is not known to the registry. */
  MANIFEST_UNKNOWN,
  /** The repository name is invalid. */
  NAME_INVALID,
  /** The repository name is not known to the registry. */
  NAME_UNKNOWN,
  /** The manifest tag is invalid. */
  TAG_INVALID,
  /** The provided length did not match the content length. */
  SIZE_INVALID,
  /** Authentication is required. */
  UNAUTHORIZED,
  /** The requested access to the resource is denied. */
  DENIED,
  /** The operation is unsupported, or its parameters are invalid. */
  UNSUPPORTED,
  /** The client sent too many requests. */
  TOOMANYREQUESTS,
  /** Any failure that has no more specific code. */
  UNKNOWN
}
