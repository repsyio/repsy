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

import { FormControl, ValidatorFn } from '@angular/forms';

import {
  LOGIN_PASSWORD_MAX_LENGTH,
  LOGIN_PASSWORD_MESSAGES,
  LOGIN_USERNAME_MESSAGES,
  loginPasswordValidators,
  loginUsernameValidators,
  PASSWORD_MESSAGES,
  PASSWORD_MISMATCH_MESSAGE,
  PASSWORD_PATTERN,
  passwordValidators,
  REQUIRED_MESSAGE,
  USERNAME_MESSAGES,
  usernameValidators,
} from './credentials.validators';

/** The Angular error keys of `value` under `validators`, sorted; empty when the value is valid. */
function errorsOf(validators: ValidatorFn[], value: string): string[] {
  return Object.keys(new FormControl(value, validators).errors ?? {}).sort();
}

describe('usernameValidators (backend UserCreateForm: 3-25 of a-z, 0-9, _ and -)', () => {
  const validators = usernameValidators();

  it('accepts the whole alphabet and both length limits', () => {
    for (const value of ['abc', 'bob_the-2nd', '0123456789', '___', '---', 'a'.repeat(25)]) {
      expect(errorsOf(validators, value)).withContext(value).toEqual([]);
    }
  });

  it('requires a value', () => {
    expect(errorsOf(validators, '')).toEqual(['required']);
  });

  it('rejects 2 characters and 26 characters', () => {
    expect(errorsOf(validators, 'ab')).toEqual(['minlength']);
    expect(errorsOf(validators, 'a'.repeat(26))).toEqual(['maxlength']);
  });

  it('rejects upper case letters, whitespace, dots, at signs and non-ASCII letters', () => {
    for (const value of ['Bob', 'bo b', ' bob', 'bob ', 'bo.b', 'bo@b', 'jürgen', 'bob\n']) {
      expect(errorsOf(validators, value)).withContext(JSON.stringify(value)).toEqual(['pattern']);
    }
  });

  it('is optional with `required: false`, but an entered value is still checked', () => {
    const optional = usernameValidators({ required: false });

    expect(errorsOf(optional, '')).toEqual([]);
    expect(errorsOf(optional, 'ci_bot-1')).toEqual([]);
    expect(errorsOf(optional, 'ab')).toEqual(['minlength']);
    expect(errorsOf(optional, 'a'.repeat(26))).toEqual(['maxlength']);
    expect(errorsOf(optional, 'CI-Bot')).toEqual(['pattern']);
  });
});

describe('passwordValidators (backend UserCreateForm, PasswordForm and LoginForm)', () => {
  const validators = passwordValidators();

  it('accepts 6 to 50 characters with a lower case letter, an upper case letter and a digit', () => {
    for (const value of ['Passw0', 'Aa1' + 'x'.repeat(47), 'Passw0rd!', 'Pässw0rd', 'Tr0ub4dor&3']) {
      expect(errorsOf(validators, value)).withContext(value).toEqual([]);
    }
  });

  it('requires a value', () => {
    expect(errorsOf(validators, '')).toEqual(['required']);
  });

  it('rejects 5 characters and 51 characters', () => {
    expect(errorsOf(validators, 'Pa1xy')).toEqual(['minlength']);
    expect(errorsOf(validators, 'Aa1' + 'x'.repeat(48))).toEqual(['maxlength']);
  });

  it('rejects a password without a lower case letter, an upper case letter or a digit', () => {
    for (const value of ['PASSWORD1', 'password1', 'Password', '!!!!!!!!']) {
      expect(errorsOf(validators, value)).withContext(value).toEqual(['pattern']);
    }
  });

  it('rejects whitespace anywhere, including a trailing one', () => {
    for (const value of ['Pass w0rd', 'Passw0rd ', ' Passw0rd', 'Passw0rd\t', 'Passw0rd\n']) {
      expect(errorsOf(validators, value)).withContext(JSON.stringify(value)).toEqual(['pattern']);
    }
  });

  it('counts only ASCII digits, like the backend', () => {
    expect(errorsOf(validators, 'Password٣')).toEqual(['pattern']);
  });

  it('is spelled once: the exported regex is the one the validators use', () => {
    expect(PASSWORD_PATTERN.test('Passw0rd')).toBeTrue();
    expect(PASSWORD_PATTERN.source).toContain('[0-9]');
    expect(PASSWORD_PATTERN.source).not.toContain('\\d');
  });
});

describe('loginUsernameValidators (backend LoginForm: 3-150 of a-z, A-Z, 0-9, @, _, - and .)', () => {
  const validators = loginUsernameValidators();

  it('accepts the whole login alphabet and both length limits', () => {
    for (const value of ['abc', 'A_b-c.d@e', '0123456789', 'a'.repeat(150)]) {
      expect(errorsOf(validators, value)).withContext(value).toEqual([]);
    }
  });

  it('rejects an empty, a short, a long or a malformed username', () => {
    expect(errorsOf(validators, '')).toEqual(['required']);
    expect(errorsOf(validators, 'ab')).toEqual(['minlength']);
    expect(errorsOf(validators, 'a'.repeat(151))).toEqual(['maxlength']);
    for (const value of ['bad user', 'bad!user', 'jürgen']) {
      expect(errorsOf(validators, value)).withContext(value).toEqual(['pattern']);
    }
  });
});

describe('loginPasswordValidators (backend LoginForm: 1-72 characters, no complexity rule)', () => {
  const validators = loginPasswordValidators();

  it('accepts any existing password that fits, whatever the creation rule says', () => {
    for (const value of [
      'a',
      'abc',
      '12345',
      'Ab1de',
      'lowercase1',
      'UPPERCASE1',
      'NoDigitsHere',
      'has space',
      '   ',
    ]) {
      expect(errorsOf(validators, value)).withContext(value).toEqual([]);
    }
    expect(errorsOf(validators, 'x'.repeat(60))).toEqual([]);
    expect(errorsOf(validators, 'x'.repeat(LOGIN_PASSWORD_MAX_LENGTH))).toEqual([]);
  });

  it('rejects an empty password and one over the BCrypt limit of 72', () => {
    expect(LOGIN_PASSWORD_MAX_LENGTH).toBe(72);
    expect(errorsOf(validators, '')).toEqual(['required']);
    expect(errorsOf(validators, 'x'.repeat(LOGIN_PASSWORD_MAX_LENGTH + 1))).toEqual(['maxlength']);
  });

  it('is looser than the creation rule, which stays as it was', () => {
    expect(errorsOf(passwordValidators(), 'abc')).not.toEqual([]);
    expect(errorsOf(validators, 'abc')).toEqual([]);
  });
});

describe('credential messages', () => {
  it('names the limits the validators enforce', () => {
    expect(USERNAME_MESSAGES.minlength).toBe('Should be minimum 3 characters');
    expect(USERNAME_MESSAGES.maxlength).toBe('Should be maximum 25 characters');
    expect(PASSWORD_MESSAGES.minlength).toBe('Should be minimum 6 characters');
    expect(PASSWORD_MESSAGES.maxlength).toBe('Should be maximum 50 characters');
    expect(LOGIN_USERNAME_MESSAGES.minlength).toBe('Should be minimum 3 characters');
    expect(LOGIN_USERNAME_MESSAGES.maxlength).toBe('Should be maximum 150 characters');
    expect(LOGIN_PASSWORD_MESSAGES.maxlength).toBe('Should be maximum 72 characters');
  });

  it('describes the alphabets, and says that a password has no whitespace', () => {
    expect(USERNAME_MESSAGES.pattern).toBe('Should contain only lowercase letters, digits, underscores and hyphens');
    expect(PASSWORD_MESSAGES.pattern).toBe(
      'Should contain at least 1 lowercase letter, 1 uppercase letter and 1 digit, and no whitespace',
    );
  });

  it('uses one sentence for a missing value and one for a mismatch', () => {
    expect(REQUIRED_MESSAGE).toBe('Should not be empty');
    expect(USERNAME_MESSAGES.required).toBe(REQUIRED_MESSAGE);
    expect(PASSWORD_MESSAGES.required).toBe(REQUIRED_MESSAGE);
    expect(LOGIN_USERNAME_MESSAGES.required).toBe(REQUIRED_MESSAGE);
    expect(LOGIN_PASSWORD_MESSAGES.required).toBe(REQUIRED_MESSAGE);
    expect(PASSWORD_MISMATCH_MESSAGE).toBe('Passwords do not match');
  });

  it('starts every message with "Should", never with the old "must" or "can" wordings', () => {
    for (const messages of [USERNAME_MESSAGES, PASSWORD_MESSAGES, LOGIN_USERNAME_MESSAGES, LOGIN_PASSWORD_MESSAGES]) {
      for (const message of Object.values(messages)) {
        expect(message).toMatch(/^Should /);
      }
    }
  });
});
