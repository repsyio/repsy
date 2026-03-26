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
package io.repsy.core.response.dtos;

import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class RestResponse<T> {
  private final @NonNull String msgId;
  @Getter private final @NonNull ResponseType type;
  @Getter private @Nullable T data;
  @Getter private String errorCode;
  @Getter private String text;

  public RestResponse(final @NonNull String msgId, final @NonNull ResponseType type) {
    this.msgId = msgId;
    this.type = type;
  }

  public void setData(final @Nullable T data) {
    this.data = data;
  }

  public void setErrorCode(final @NonNull String errorCode) {
    this.errorCode = errorCode;
  }

  public void setText(final @NonNull String text) {
    this.text = text;
  }

  public @NonNull String getMsgId() {
    return this.msgId;
  }
}
