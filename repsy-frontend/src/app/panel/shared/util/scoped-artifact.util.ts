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
/** The two halves of a scoped npm artifact name such as {@code @acme/widget}. */
export interface ScopedArtifactName {
  /** The scope without the {@code @}. */
  scope: string;
  /** The package name inside the scope. */
  name: string;
}

/**
 * Splits a scoped npm artifact name into scope and name, or returns null for an unscoped name. The
 * scan routes address a scoped artifact as {@code /scopes/{scope}/artifacts/{artifactName}}, so the
 * name never travels as one path segment with a slash in it.
 */
export function splitScopedArtifactName(artifactName: string): ScopedArtifactName | null {
  if (!artifactName.startsWith('@')) {
    return null;
  }

  const slashIndex = artifactName.indexOf('/');

  if (slashIndex < 2 || slashIndex === artifactName.length - 1) {
    return null;
  }

  return { scope: artifactName.slice(1, slashIndex), name: artifactName.slice(slashIndex + 1) };
}
