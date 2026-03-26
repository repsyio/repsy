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

import { Md5 } from 'ts-md5';

export function gravatarUrl(email: string | null | undefined, size = 110): string {
  if (!email) {
    return `https://www.gravatar.com/avatar/?s=${size}&d=identicon`;
  }

  const normalized = email.trim().toLowerCase();
  const hash = Md5.hashStr(normalized);

  return `https://www.gravatar.com/avatar/${hash}?s=${size}&d=identicon`;
}
