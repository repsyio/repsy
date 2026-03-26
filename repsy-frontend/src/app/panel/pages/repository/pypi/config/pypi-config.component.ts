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
  selector: 'app-pypi-config',
  standalone: true,
  imports: [MarkdownComponent, MarkdownComponent],
  styleUrl: './pypi-config.component.css',
  templateUrl: './pypi-config.component.html',
})
export class PypiConfigComponent implements OnInit, OnChanges {
  @Input() baseUrl: string;
  @Input() username = '<username>';
  @Input() repoName = '<repo_name>';
  @Input() open: boolean;
  @Input() deployToken: boolean;
  @Output() openChange = new EventEmitter<boolean>();

  markdown: string;

  ngOnInit(): void {
    this.updateMarkdown();
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['baseUrl'] || changes['username'] || changes['repoName'] || changes['deployToken']) {
      this.updateMarkdown();
    }
  }

  closeModal(): void {
    this.openChange.emit(false);
  }

  private updateMarkdown(): void {
    this.markdown = `
If you don't have already, create .pypirc configuration file;

\`\`\`bash
touch $HOME/.pypirc
\`\`\`

Add repository configuration;

\`\`\`ini
[distutils]
index-servers = repsy-default

[repsy-default]
repository=${this.baseUrl}/${this.repoName}/simple
username=${this.username}
\`\`\`

Add repository password;

\`\`\`bash
python -m keyring set repsy-default ${this.username}
\`\`\`

then, enter the ${this.deployToken ? ' __***your deploy token***__' : ' __***YOUR_PASSWORD***__'}

Pip supports keyring library so you don't have to do any additional authentication to install packages from private repositories.
Keyring library is installed with twine, if you have twine you don't have to install it.
You're all set up and ready to go!`;
  }
}
