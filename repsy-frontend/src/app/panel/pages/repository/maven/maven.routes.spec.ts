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
import { MavenArtifactsGroupListComponent } from './artifacts/group/maven-artifacts-group-list.component';
import { MavenArtifactsListComponent } from './artifacts/list/maven-artifacts-list.component';
import { MavenArtifactsVersionDetailComponent } from './artifacts/version-detail/maven-artifacts-version-detail.component';
import { MavenArtifactsVersionListComponent } from './artifacts/version-list/maven-artifacts-version-list.component';
import { MavenBrowserComponent } from './browser/maven-browser.component';
import { MavenComponent } from './maven.component';
import { MAVEN_ROUTES } from './maven.routes';

describe('MAVEN_ROUTES', () => {
  describeRouteTree(
    () => MAVEN_ROUTES,
    [
      { path: '', component: MavenComponent },
      { path: '', component: MavenArtifactsGroupListComponent, title: 'repsy | Maven Groups', full: true },
      { path: 'settings', component: RepositorySettingsComponent, title: 'repsy | Repository Settings', full: true },
      { path: 'browser', component: MavenBrowserComponent, title: 'repsy | Browser', full: true },
      { path: ':group' },
      { path: ':group', component: MavenArtifactsListComponent, title: 'repsy | Maven Artifacts', full: true },
      { path: ':group/:artifact' },
      {
        path: ':group/:artifact',
        component: MavenArtifactsVersionListComponent,
        title: 'repsy | Maven Artifact Versions',
        full: true,
      },
      {
        path: ':group/:artifact/:version',
        component: MavenArtifactsVersionDetailComponent,
        title: 'repsy | Maven Version Detail',
        full: true,
      },
    ],
  );
});
