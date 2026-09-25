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

import { environment } from '../../../../../../environments/environment';
import { MavenConfigComponent } from './maven-config.component';

describe('MavenConfigComponent Apache Ivy snippet (RPS-1332)', () => {
  let component: MavenConfigComponent;
  const originalRepoBaseUrl = environment.repoBaseUrl;

  beforeEach(() => {
    environment.repoBaseUrl = 'https://repo.example.com:9090';
    component = new MavenConfigComponent();
    component.repoName = 'my-repo';
    component.username = 'alice';
    component.deployToken = false;
  });

  afterEach(() => {
    environment.repoBaseUrl = originalRepoBaseUrl;
  });

  it('keeps the settings.xml and pom.xml instructions in front of the Ivy section', () => {
    component.ngOnInit();

    const ivyStart = component.markdown.indexOf('### Apache Ivy');
    expect(ivyStart).toBeGreaterThan(0);
    expect(component.markdown.indexOf('.m2/settings.xml')).toBeLessThan(ivyStart);
    expect(component.markdown.indexOf('mvn compile deploy')).toBeLessThan(ivyStart);
  });

  it('renders the m2 resolver with the repository URL and the Basic-realm credentials', () => {
    component.ngOnInit();

    expect(component.markdown).toContain(`<ivysettings>
  <settings defaultResolver="repsy"/>
  <credentials host="repo.example.com"
               realm="Repsy"
               username="alice"
               passwd="YOUR_PASSWORD"/>
  <resolvers>
    <ibiblio name="repsy" m2compatible="true" root="https://repo.example.com:9090/my-repo/"/>
  </resolvers>
</ivysettings>`);
  });

  it('gives Ivy the host without the port, since it matches credentials by host and realm', () => {
    environment.repoBaseUrl = 'http://localhost:9090';

    component.ngOnInit();

    expect(component.markdown).toContain('<credentials host="localhost"');
    expect(component.markdown).toContain('root="http://localhost:9090/my-repo/"');
    expect(component.markdown).not.toContain('host="localhost:9090"');
  });

  it('falls back to the configured value as the host when the repository URL cannot be parsed', () => {
    environment.repoBaseUrl = 'repo.internal';

    component.ngOnInit();

    expect(component.markdown).toContain('<credentials host="repo.internal"');
  });

  it('renders the POM publication, makepom and publishivy="false"', () => {
    component.ngOnInit();

    expect(component.markdown).toContain('<artifact name="my-lib" type="pom" ext="pom" conf="default"/>');
    expect(component.markdown).toContain('<ivy:makepom ivyfile="ivy.xml" pomfile="build/my-lib.pom"/>');
    expect(component.markdown).toContain('<ivy:publish resolver="repsy" pubrevision="1.0.0" publishivy="false">');
    expect(component.markdown).toContain('400 invalidArtifactPath');
    expect(component.markdown).toContain('as `ivy-<revision>.xml`');
    expect(component.markdown).not.toContain('onto the POM path');
  });

  it('leaves overwrite at Ivy default and says when to set it', () => {
    component.ngOnInit();

    const ivy = component.markdown.substring(component.markdown.indexOf('### Apache Ivy'));

    expect(ivy).not.toContain('overwrite="true">');
    expect(ivy).toContain("Ivy's `overwrite` defaults to `false`");
    expect(ivy.replace(/\s+/g, ' ')).toContain('set `overwrite="true"` on `ivy:publish` to republish a SNAPSHOT');
    expect(ivy).not.toContain('answers that with `200`');
  });

  it('tells a module with dependencies to map its configuration to a POM scope', () => {
    component.ngOnInit();

    expect(component.markdown).toContain('<mapping conf="default" scope="compile"/>');
    expect(component.markdown).toContain('every\ndependency is written to the POM as optional');
  });

  it('documents that Repsy answers the artifact-level maven-metadata.xml Ivy does not upload', () => {
    component.ngOnInit();

    const markdown = component.markdown.replace(/\s+/g, ' ');
    expect(markdown).toContain('Ivy uploads no `maven-metadata.xml`');
    expect(markdown).toContain('answers a request for the artifact-level one from the versions it has registered');
    expect(markdown).not.toContain('prefer fixed versions');
  });

  it('shows the deploy token as the Ivy password and says the username can be empty', () => {
    component.deployToken = true;

    component.ngOnInit();

    const ivy = component.markdown.substring(component.markdown.indexOf('### Apache Ivy'));
    expect(ivy).toContain('passwd="YOUR_DEPLOY_TOKEN"');
    expect(ivy).not.toContain('YOUR_PASSWORD');
    expect(ivy).toContain('the username can be empty (`username=""`)');
  });

  it('does not mention an empty username for a user password', () => {
    component.ngOnInit();

    expect(component.markdown).not.toContain('the username can be empty (`username=""`)');
  });

  it('updates the Ivy section when the username or repository changes after init', () => {
    component.ngOnInit();

    component.username = 'bob';
    component.repoName = 'other-repo';
    component.ngOnChanges({
      username: { currentValue: 'bob', previousValue: 'alice', firstChange: false, isFirstChange: () => false },
    });

    expect(component.markdown).toContain('username="bob"');
    expect(component.markdown).toContain('root="https://repo.example.com:9090/other-repo/"');
    expect(component.markdown).not.toContain('my-repo');
  });
});
