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

import { describeRouteTree } from '../../../../shared/testing/route-spec-helpers';
import { RepositorySettingsComponent } from '../repo-settings/repository-settings.component';
import { RubyGemsListComponent } from './gems/list/ruby-gems-list.component';
import { RubyGemsVersionDetailComponent } from './gems/version-detail/ruby-gems-version-detail.component';
import { RubyGemsVersionListComponent } from './gems/version-list/ruby-gems-version-list.component';
import { RubyComponent } from './ruby.component';
import { RUBY_ROUTES } from './ruby.routes';

describe('RUBY_ROUTES', () => {
  describeRouteTree(
    () => RUBY_ROUTES,
    [
      { path: '', component: RubyComponent },
      { path: '', component: RubyGemsListComponent, title: 'repsy | Ruby Gems', full: true },
      {
        path: 'settings',
        component: RepositorySettingsComponent,
        title: 'repsy | Ruby Repository Settings',
        full: true,
      },
      { path: ':gem' },
      { path: ':gem', component: RubyGemsVersionListComponent, title: 'repsy | Ruby Gem Versions', full: true },
      {
        path: ':gem/:version',
        component: RubyGemsVersionDetailComponent,
        title: 'repsy | Ruby Gem Version Detail',
        full: true,
      },
    ],
  );
});
