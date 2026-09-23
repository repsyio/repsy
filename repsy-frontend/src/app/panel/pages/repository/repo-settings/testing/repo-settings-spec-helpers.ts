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

import { FormControl, FormGroup } from '@angular/forms';

export interface ParentFormValues {
  privateRepository: boolean;
  allowOverride: boolean;
  securityScanEnabled: boolean;
  releases: boolean;
  snapshots: boolean;
}

const DEFAULTS: ParentFormValues = {
  privateRepository: false,
  allowOverride: true,
  securityScanEnabled: true,
  releases: false,
  snapshots: false,
};

/** The `generalSettingsForm` the settings page hands to its children: no releases or snapshots controls. */
export function generalParentForm(values: Partial<ParentFormValues> = {}): FormGroup {
  const { privateRepository, allowOverride, securityScanEnabled } = { ...DEFAULTS, ...values };
  return new FormGroup({
    privateRepository: new FormControl(privateRepository),
    allowOverride: new FormControl(allowOverride),
    securityScanEnabled: new FormControl(securityScanEnabled),
  });
}

/** The `mavenSettingsForm` the settings page hands to its children for Maven and NuGet repositories. */
export function releaseAwareParentForm(values: Partial<ParentFormValues> = {}): FormGroup {
  const { privateRepository, allowOverride, securityScanEnabled, releases, snapshots } = { ...DEFAULTS, ...values };
  return new FormGroup({
    privateRepository: new FormControl(privateRepository),
    releases: new FormControl(releases),
    snapshots: new FormControl(snapshots),
    allowOverride: new FormControl(allowOverride),
    securityScanEnabled: new FormControl(securityScanEnabled),
  });
}

/**
 * The form a spy was last called with, as the plain object that goes over the wire: the components build their
 * payloads from DTO classes (`RepoSettingsForm`, `MavenRepoSettingsForm`), which `toEqual` would otherwise tell apart
 * from an object literal, and JSON drops an unset optional field such as `releases` on a non-Maven/NuGet repository
 * (the backend refuses it there, RPS-1210).
 */
export function lastSentForm(spy: jasmine.Spy, argIndex: number): Record<string, unknown> {
  return JSON.parse(JSON.stringify(spy.calls.mostRecent().args[argIndex])) as Record<string, unknown>;
}
