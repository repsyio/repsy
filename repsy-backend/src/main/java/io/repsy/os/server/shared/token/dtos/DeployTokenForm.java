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
package io.repsy.os.server.shared.token.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.repsy.os.shared.token.configs.validators.ValidExpirationDate;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@NoArgsConstructor
public class DeployTokenForm {
  @NonNull
  @Size(min = 1, max = 80)
  private String name;

  @Size(max = 150)
  private String username;

  @Size(max = 500)
  private String description;

  @JsonProperty("read_only")
  private boolean readOnly;

  @JsonProperty("expiration_date")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  @ValidExpirationDate
  private Instant expirationDate;
}
