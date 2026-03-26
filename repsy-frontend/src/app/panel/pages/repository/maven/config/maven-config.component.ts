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

import { environment } from '../../../../../../environments/environment';
import { MarkdownComponent } from '../../../../shared/components/markdown/markdown.component';

@Component({
  selector: 'app-maven-config',
  standalone: true,
  imports: [MarkdownComponent],
  templateUrl: './maven-config.component.html',
  styleUrl: './maven-config.component.css',
})
export class MavenConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() deployToken: boolean;
  @Input() open: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  public markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['username'] || changes['repoName'] || changes['deployToken']) {
      this.updateMarkdown();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    this.markdown = this.getAuthMarkdown();
  }

  private getAuthMarkdown(): string {
    return `
You should configure your .m2/settings.xml at your home directory;
\`\`\`xml
<settings>
  ...
  <servers>
    ...
    <server>
      <id>repsy</id>
      <username>${this.username}</username>
      <password>${this.deployToken ? 'YOUR_DEPLOY_TOKEN' : 'YOUR_PASSWORD'}</password>
    </server>
    ...
  </servers>
  ...
</settings>
\`\`\`

${
  this.deployToken
    ? `
  ${this.deployToken ? 'or If you are using a deploy token, the username can be empty;' : ''}
  \`\`\`xml
<settings>
  ...
  <servers>
    ...
    <server>
      <id>repsy</id>
      <username>CAN_BE_EMPTY</username>
      <password>YOUR_DEPLOY_TOKEN</password>
    </server>
    ...
  </servers>
  ...
</settings>
\`\`\`
  `
    : ''
}

After adding server definition to your .m2/settings.xml,
you need to configure your project pom.xml file;

\`\`\`xml
<project ...>
  ...
  <distributionManagement>
    <repository>
      <id>repsy</id>
      <name>My Private Maven Repository on Repsy</name>
      <url>${environment.repoBaseUrl}/${this.repoName}</url>
    </repository>
  </distributionManagement>
  ...
</project>
\`\`\`

Now, you're ready to deploy;

\`\`\`bash
mvn compile deploy
\`\`\`

For downloading packages, we need to add one more repository definition;

\`\`\`xml
<project ...>
  ...
  <repositories>
    ...
    <repository>
      <id>repsy</id>
      <name>My Private Maven Repository on Repsy</name>
      <url>${environment.repoBaseUrl}/${this.repoName}</url>
    </repository>
    ...
  </repositories>
  ...
</project>
\`\`\`
`;
  }
}
