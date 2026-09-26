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
import { CargoConfigComponent } from './cargo-config.component';

describe('CargoConfigComponent (RPS-1598)', () => {
  let component: CargoConfigComponent;

  beforeEach(() => {
    component = new CargoConfigComponent();
    component.baseUrl = 'https://repo.example.com';
    component.repoName = 'my-repo';
    component.deployToken = false;
    component.ngOnInit();
  });

  it('points the registry at the sparse index of the repo', () => {
    expect(component.markdown).toContain('repsy = { index = "sparse+https://repo.example.com/my-repo/" }');
  });

  it('teaches the stdin form of cargo login, with no token on the command line', () => {
    expect(component.markdown).toContain('```bash\ncargo login --registry repsy\n```');
    expect(component.markdown).not.toContain('cargo login --registry repsy <');
    expect(component.markdown).not.toContain('YOUR_DEPLOY_TOKEN');
  });

  it('explains the prompt, where the token is saved and the CI variable', () => {
    expect(component.markdown).toContain('Cargo asks for the token: paste it and press Enter.');
    expect(component.markdown).toContain('`$HOME/.cargo/credentials.toml`');
    expect(component.markdown).toContain('In CI, set `CARGO_REGISTRIES_REPSY_TOKEN` instead (see the docs).');
  });

  it('renders the same body for the deploy-token variant', () => {
    const configuration = component.markdown;

    component.deployToken = true;
    component.ngOnChanges({ deployToken: { currentValue: true } as never });

    expect(component.markdown).toBe(configuration);
  });

  it('re-renders the index URL when the repo changes', () => {
    component.repoName = 'other-repo';
    component.ngOnChanges({ repoName: { currentValue: 'other-repo' } as never });

    expect(component.markdown).toContain('sparse+https://repo.example.com/other-repo/');
  });

  it('emits false when the dialog is closed', () => {
    const emitted: boolean[] = [];
    component.openChange.subscribe((value) => emitted.push(value));

    component.closeModal();

    expect(emitted).toEqual([false]);
  });
});
