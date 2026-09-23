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

import { Component, EventEmitter, Input, OnChanges, OnInit, Output, SimpleChanges } from '@angular/core';

import { MarkdownComponent } from '../../../../shared/components/markdown/markdown.component';

@Component({
  selector: 'app-npm-config',
  standalone: true,
  imports: [MarkdownComponent, MarkdownComponent],
  styleUrl: './npm-config.component.css',
  templateUrl: './npm-config.component.html',
})
export class NpmConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() scopeName = '<scope_name>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  public markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (
      changes['baseUrl'] ||
      changes['username'] ||
      changes['repoName'] ||
      changes['scopeName'] ||
      changes['deployToken']
    ) {
      this.updateMarkdown();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    // A trailing slash matters here: npm matches a //host[:port]/path/:_authToken key against the
    // registry URL by the longest //host[:port]/path/ prefix (see the npmrc scoping rules,
    // https://docs.npmjs.com/cli/v11/configuring-npm/npmrc). Without it, the auth key in
    // getDeployTokenAuthInfo() never matches this registry URL and every request goes out
    // unauthenticated (RPS-1206).
    const registryUrl = `${this.baseUrl}/${this.repoName}/`;

    this.markdown = `
Either public or private, you must authenticate to publish packages to your registry. Authentication is also
required to install packages from private registries;

\`\`\`bash
npm login --registry ${registryUrl}
\`\`\`

${this.deployToken ? this.getDeployTokenAuthInfo(registryUrl) : ''}

To use your registry only for scoped packages, for each different scope, you should configure npm to use your
registry for all operations related to that scope;

\`\`\`bash
npm config set ${this.scopeName ? this.scopeName : '<scope_name>'}:registry ${registryUrl}
\`\`\`

And to use your registry as the main registry;

\`\`\`bash
npm config set registry ${registryUrl}
\`\`\`

That is all, now you can start using your registry.`;
  }

  private getDeployTokenAuthInfo(registryUrl: string): string {
    // Derived from the same registryUrl (and so the same baseUrl) as the registry lines above,
    // not from a different source -- otherwise the host here can silently drift from the one npm
    // actually connects to (RPS-1206).
    const registryHostAndPath = registryUrl.replace(/^https?:\/\//, '');

    return `
<small>***username:*** ${this.username} or ANY\\_USERNAME <br> ***password:*** YOUR\\_DEPLOY\\_TOKEN</small>

or you can bypass the login operation

**Bypass Login**

In automated processes like pipelines, interactive login sessions can be challenging.
Therefore, you can directly use your repsy deploy token as the npm authToken.

If the file does not already exist, create a ___.npmrc___ file in your home directory:

\`\`\`bash
touch $HOME/.npmrc
\`\`\`

Then, add the following line to your ___.npmrc___ file

\`\`\`bash
//${registryHostAndPath}:_authToken=<YOUR_DEPLOY_TOKEN>
\`\`\`
`;
  }
}
