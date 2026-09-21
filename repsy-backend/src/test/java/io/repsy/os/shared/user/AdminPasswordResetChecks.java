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
package io.repsy.os.shared.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.os.shared.user.entities.User;
import java.util.regex.Pattern;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * What {@link AdminUserInitializerIT} (PostgreSQL) and {@link H2AdminUserInitializerIT} (embedded
 * H2) share: the statements of the README's "Forgot admin password?" recovery and the way to read
 * the new password the initializer logs. Both databases must accept the same statements and give
 * the same result, and running the same assertions against each is what proves it (RPS-1026,
 * RPS-1099).
 */
final class AdminPasswordResetChecks {

  /** The statement from the README, for every admin. */
  static final String RESET_ALL_ADMINS_SQL = "UPDATE users SET hash = '' WHERE role = 'ADMIN'";

  /** The statement from the README, for a single admin. */
  static final String RESET_ONE_ADMIN_SQL =
      "UPDATE users SET hash = '' WHERE role = 'ADMIN' AND username = ?";

  /** What an operator must not use: {@code users.hash} is {@code NOT NULL} in both schemas. */
  static final String NULL_HASH_SQL = "UPDATE users SET hash = NULL WHERE role = 'ADMIN'";

  private AdminPasswordResetChecks() {}

  /** The password the initializer logged for {@code admin}, which fails if it logged none. */
  static String loggedPasswordOf(final User admin, final CapturedOutput output) {
    final var pattern =
        Pattern.compile(
            "Admin password has been reset for user "
                + Pattern.quote(admin.getUsername())
                + "\\. New password: (\\S+)");
    final var matcher = pattern.matcher(output.getAll());

    assertThat(matcher.find())
        .as("the reset of %s is logged with its new password", admin.getUsername())
        .isTrue();

    return matcher.group(1);
  }
}
