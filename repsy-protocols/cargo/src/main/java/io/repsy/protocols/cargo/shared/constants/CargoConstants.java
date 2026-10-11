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
package io.repsy.protocols.cargo.shared.constants;

public final class CargoConstants {

  private CargoConstants() {}

  /**
   * Request attribute set by a Cargo web API handler whose failures must reach the client in
   * Cargo's error body ({@code {"errors":[{"detail":"..."}]}}), which cargo prints, instead of the
   * RestResponse envelope that {@code ErrorHandler} renders for a protocol route (RPS-2104).
   */
  public static final String ERROR_BODY_ATTRIBUTE = "io.repsy.protocols.cargo.errorBody";
}
