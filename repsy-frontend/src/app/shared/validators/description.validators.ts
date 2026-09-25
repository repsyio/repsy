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
 * The one description rule of the panel (RPS-1265), used by the create-repository, repository
 * settings and create-deploy-token forms. Like the username and password rules in
 * `credentials.validators.ts` it is read off the backend (`openapi-spec.yaml`, the Bean Validation
 * constraints of the generated DTOs; the panel never needs to be stricter and must not be looser):
 *
 * <pre>
 * Field                                Backend (form)                   Panel (this file)
 * ----------------------------------   ------------------------------   ---------------------------
 * repository description, create       RepoCreateRequest: up to 500     DESCRIPTION_*: at most 500
 * repository description, settings     RepoDescriptionForm: up to 500   characters, optional
 * deploy token description             DeployTokenForm: up to 500
 * </pre>
 *
 * There is no lower bound and no pattern: an empty or missing description is valid. The message is
 * kept next to the rule, so every form shows the same sentence.
 *
 * Every description textarea enforces the rule with the validator and a character counter, and none
 * has a `maxlength` attribute: the browser would cut typed and pasted text silently, and the message
 * below could never show.
 */
export const DESCRIPTION_MAX_LENGTH = 500;

export const DESCRIPTION_MAX_MESSAGE = `Should be maximum ${DESCRIPTION_MAX_LENGTH} characters`;

export function descriptionValidators(): ValidatorFn[] {
  return [Validators.maxLength(DESCRIPTION_MAX_LENGTH)];
}
