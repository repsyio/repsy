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
 * The description of a repository or a deploy token: at most 500 characters, as the backend
 * (`RepoCreateForm`, `RepoDescriptionForm`, `DeployTokenForm`) has it.
 *
 * Every description textarea enforces this with the validator and a character counter, and none has
 * a `maxlength` attribute: the browser would cut typed and pasted text silently, and the message
 * below could never show.
 */
export const DESCRIPTION_MAX_LENGTH = 500;

export const DESCRIPTION_MAX_MESSAGE = `Should be maximum ${DESCRIPTION_MAX_LENGTH} characters`;

export function descriptionValidators(): ValidatorFn[] {
  return [Validators.maxLength(DESCRIPTION_MAX_LENGTH)];
}
