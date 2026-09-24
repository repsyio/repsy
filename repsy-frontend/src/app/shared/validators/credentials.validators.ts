///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.

import { ValidatorFn, Validators } from '@angular/forms';

/**
 * The one set of username and password rules of the panel (RPS-1265), used by the login,
 * create-user, edit-user, profile and deploy-token forms. The rules were read off the backend
 * (`openapi-spec.yaml`, the Bean Validation constraints of the generated form DTOs; the panel never
 * needs to be stricter and must not be looser), not invented:
 *
 * <pre>
 * Field                               Backend (form)                        Panel (this file)
 * ---------------------------------   -----------------------------------   ------------------------------
 * username, create/edit/rename        UserCreateForm, UserUpdateForm,       USERNAME_*: 3-25 characters of
 *                                     UpdateUsernameForm: 3-25,             [a-z0-9_-]
 *                                     ^[a-z0-9_-]+$
 * password, create/change             UserCreateForm and PasswordForm:      PASSWORD_*: 6-50, at least one
 *                                     6-50, at least one lower case         lower case letter, upper case
 *                                     letter, one upper case letter         letter and digit, no
 *                                     and one digit, no whitespace          whitespace
 * password, login                     LoginForm: 1-72, no other rule        LOGIN_PASSWORD_*: required, at
 *                                                                           most 72 characters
 * username, login                     LoginForm: 3-150, [a-zA-Z0-9@_.-]     LOGIN_USERNAME_*: the same. It
 *                                                                           is wider than what can be
 *                                                                           created, which does no harm
 * deploy token username (optional)    DeployTokenForm: up to 80, any        USERNAME_*: the create-user
 *                                     character                             rule, narrower than the
 *                                                                           backend on purpose
 * </pre>
 *
 * The password regex is written once, here: it used to be spelled `[0-9]` in some forms and `\d` in
 * others (the same set, ASCII digits, in JavaScript and in the backend's java.util.regex).
 *
 * The login password is the one deliberate exception to "the same rule everywhere" (RPS-1308). The
 * complexity rule applies where a password is SET; at login the form holds an EXISTING password, which
 * may predate the policy or come from an `ADMIN_INITIAL_PASSWORD` shorter than 6 or longer than 50
 * characters, and must still be able to log in. So the login form checks only the shape (something
 * is typed, at most 72 characters, the BCrypt input limit) and lets the server answer 401 for a wrong
 * password, weak or strong alike. It does not tell the password policy to somebody who is not signed in.
 * The backend also refuses a password longer than 72 bytes (multi-byte characters) with a 400.
 *
 * The messages are kept next to the rules, so every form shows the same sentence for the same rule.
 * A template prefixes them with a bullet and picks one by the Angular validator error key
 * (`required`, `minlength`, `maxlength`, `pattern`).
 */

export const USERNAME_MIN_LENGTH = 3;
export const USERNAME_MAX_LENGTH = 25;
export const USERNAME_PATTERN = /^[a-z0-9_-]+$/;

export const PASSWORD_MIN_LENGTH = 6;
export const PASSWORD_MAX_LENGTH = 50;
// One regex for every form, including the login one.
export const PASSWORD_PATTERN = /^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/;

// The login form only checks that a password is typed and fits BCrypt's 72 bytes. No minimum, no pattern.
export const LOGIN_PASSWORD_MAX_LENGTH = 72;

export const LOGIN_USERNAME_MIN_LENGTH = 3;
export const LOGIN_USERNAME_MAX_LENGTH = 150;
export const LOGIN_USERNAME_PATTERN = /^[a-zA-Z0-9@_\-.]+$/;

export const REQUIRED_MESSAGE = 'Should not be empty';
export const PASSWORD_MISMATCH_MESSAGE = 'Passwords do not match';

export const USERNAME_MESSAGES = {
  required: REQUIRED_MESSAGE,
  minlength: `Should be minimum ${USERNAME_MIN_LENGTH} characters`,
  maxlength: `Should be maximum ${USERNAME_MAX_LENGTH} characters`,
  pattern: 'Should contain only lowercase letters, digits, underscores and hyphens',
} as const;

export const PASSWORD_MESSAGES = {
  required: REQUIRED_MESSAGE,
  minlength: `Should be minimum ${PASSWORD_MIN_LENGTH} characters`,
  maxlength: `Should be maximum ${PASSWORD_MAX_LENGTH} characters`,
  pattern: 'Should contain at least 1 lowercase letter, 1 uppercase letter and 1 digit, and no whitespace',
} as const;

export const LOGIN_PASSWORD_MESSAGES = {
  required: REQUIRED_MESSAGE,
  maxlength: `Should be maximum ${LOGIN_PASSWORD_MAX_LENGTH} characters`,
} as const;

export const LOGIN_USERNAME_MESSAGES = {
  required: REQUIRED_MESSAGE,
  minlength: `Should be minimum ${LOGIN_USERNAME_MIN_LENGTH} characters`,
  maxlength: `Should be maximum ${LOGIN_USERNAME_MAX_LENGTH} characters`,
  pattern: 'Should not contain invalid characters',
} as const;

/** The username of a new or renamed account. `required: false` is for an optional field (token). */
export function usernameValidators(options: { required?: boolean } = {}): ValidatorFn[] {
  return [
    ...(options.required === false ? [] : [Validators.required]),
    Validators.minLength(USERNAME_MIN_LENGTH),
    Validators.maxLength(USERNAME_MAX_LENGTH),
    Validators.pattern(USERNAME_PATTERN),
  ];
}

/** A new password (create user, change password). */
export function passwordValidators(): ValidatorFn[] {
  return [
    Validators.required,
    Validators.minLength(PASSWORD_MIN_LENGTH),
    Validators.maxLength(PASSWORD_MAX_LENGTH),
    Validators.pattern(PASSWORD_PATTERN),
  ];
}

/** The username typed on the login form: the backend's LoginForm rule. */
export function loginUsernameValidators(): ValidatorFn[] {
  return [
    Validators.required,
    Validators.minLength(LOGIN_USERNAME_MIN_LENGTH),
    Validators.maxLength(LOGIN_USERNAME_MAX_LENGTH),
    Validators.pattern(LOGIN_USERNAME_PATTERN),
  ];
}

/** The password typed on the login form: only its shape, whatever the creation rule is (RPS-1308). */
export function loginPasswordValidators(): ValidatorFn[] {
  return [Validators.required, Validators.maxLength(LOGIN_PASSWORD_MAX_LENGTH)];
}
