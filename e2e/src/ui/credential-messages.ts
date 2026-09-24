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

/**
 * The one set of validation sentences the panel shows for a username, a password and a description
 * (RPS-1265), as the frontend keeps them in `repsy-frontend/src/app/shared/validators`. Every form
 * (login, create user, edit user, profile, deploy token, repository) shows these and no other
 * wording, each behind a "• " bullet, so a spec asserts the text once here and a form that keeps its
 * own sentence fails against it.
 *
 * The rules behind them mirror the backend (`openapi-spec.yaml`): a username is 3-25 characters of
 * `[a-z0-9_-]`, a password 6-50 with a lower case letter, an upper case letter and a digit and no
 * whitespace, a description at most 500 characters. The login form's username is the backend's wider
 * LoginForm alphabet (3-150 of `[a-zA-Z0-9@_.-]`).
 */
export type ValidatorKey = 'required' | 'minlength' | 'maxlength' | 'pattern';

export const REQUIRED_TEXT = 'Should not be empty';

export const USERNAME_TEXT: Record<ValidatorKey, string> = {
  required: REQUIRED_TEXT,
  minlength: 'Should be minimum 3 characters',
  maxlength: 'Should be maximum 25 characters',
  pattern: 'Should contain only lowercase letters, digits, underscores and hyphens',
};

export const PASSWORD_TEXT: Record<ValidatorKey, string> = {
  required: REQUIRED_TEXT,
  minlength: 'Should be minimum 6 characters',
  maxlength: 'Should be maximum 50 characters',
  pattern:
    'Should contain at least 1 lowercase letter, 1 uppercase letter and 1 digit, and no whitespace',
};

export const LOGIN_USERNAME_TEXT: Record<ValidatorKey, string> = {
  required: REQUIRED_TEXT,
  minlength: 'Should be minimum 3 characters',
  maxlength: 'Should be maximum 150 characters',
  pattern: 'Should not contain invalid characters',
};

export const MISMATCH_TEXT = 'Passwords do not match';
export const DESCRIPTION_MAX_TEXT = 'Should be maximum 500 characters';

/** What a message reads on screen: the template puts a bullet in front of the sentence. */
export const bulleted = (text: string): string => `• ${text}`;
