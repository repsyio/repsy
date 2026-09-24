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
package io.repsy.protocols.maven.shared.artifact.dtos;

/** What became of a signature that was offered for verification (RPS-1188). */
public enum SignatureOutcome {

  /** It was verified against the stored file, and is to be stored like any file. */
  VERIFIED,

  /**
   * It reached a repo verifying every signature before the file it signs (or the POM that registers
   * its version) and was kept back unverified. Nothing is stored for it: it is verified, written
   * and recorded when that file arrives.
   */
  PARKED
}
