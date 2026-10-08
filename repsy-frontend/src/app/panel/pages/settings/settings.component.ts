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

import { Component } from '@angular/core';
import { RouterModule } from '@angular/router';

import { BreadcrumbComponent } from '../../shared/components/breadcrumb/breadcrumb.component';
import { AccessTokensComponent } from './access-tokens/access-tokens.component';

/**
 * RPS-2002: the account settings that are not the profile itself. Lives under `/profile/settings`
 * (`profile` is a reserved repository name, `settings` is not, so no new reserved name is needed).
 * One section for now; the navigation lists the sections so later ones are added in one place.
 */
@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [RouterModule, BreadcrumbComponent, AccessTokensComponent],
  templateUrl: './settings.component.html',
})
export class SettingsComponent {
  /** `id` is the element id of the section, used as the link fragment and in the test id. */
  public readonly sections = [{ id: 'access-tokens', title: 'Access tokens' }];
}
