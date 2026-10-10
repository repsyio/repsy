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
///
import { RepoType as ApiRepoType } from '../../../../generated/api';

/**
 * The generated (upper-case) repository type for a type as the UI spells it ('maven', 'MAVEN'), or
 * `undefined` for anything that is not a repository type ('all', ''). The spellings come from the
 * generated enum, so a type added to the API is found without touching this function.
 */
export function toApiRepoType(type: string | null | undefined): ApiRepoType | undefined {
  if (!type) {
    return undefined;
  }

  const wanted = type.toLowerCase();
  return Object.values(ApiRepoType).find((apiType) => apiType.toLowerCase() === wanted);
}

/**
 * A repository type as the UI spells it in a ROUTE: the lower-case slug (`maven`). The API's
 * canonical spelling is upper case (`MAVEN`) and it accepts both on input, so a slug is only ever
 * needed for URLs and route parameters; an API call takes the generated enum value.
 */
export type RepoRouteSlug = Lowercase<ApiRepoType>;

/** The route slug of a repository type given in either case, or `undefined` for a non-type. */
export function toRouteSlug(type: string | null | undefined): RepoRouteSlug | undefined {
  const apiType = toApiRepoType(type);
  return apiType ? (apiType.toLowerCase() as RepoRouteSlug) : undefined;
}
