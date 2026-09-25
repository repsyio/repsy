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
import { DialogDirective } from '../../../../shared/directives/dialog.directive';

@Component({
  selector: 'app-maven-config',
  standalone: true,
  imports: [DialogDirective, MarkdownComponent],
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
    this.markdown = this.getAuthMarkdown() + this.getIvyMarkdown();
  }

  // The host part of the repository URL, for Ivy's <credentials host="...">: Ivy matches it against the
  // host of the request without the port, so `http://localhost:9090` gives `localhost`.
  private repoHost(): string {
    try {
      return new URL(environment.repoBaseUrl).hostname;
    } catch {
      return environment.repoBaseUrl;
    }
  }

  // The Apache Ivy setup (RPS-1332): what was proven against a real Repsy with Ivy 2.5.3 and Ant 1.10.
  private getIvyMarkdown(): string {
    const password = this.deployToken ? 'YOUR_DEPLOY_TOKEN' : 'YOUR_PASSWORD';
    const tokenNote = this.deployToken ? '\nWith a deploy token the username can be empty (`username=""`).\n' : '';
    return `

### Apache Ivy

For [Apache Ivy](https://ant.apache.org/ivy/), configure an \`ibiblio\` resolver in Maven-compatible mode
and the credentials in your \`ivysettings.xml\`;

\`\`\`xml
<ivysettings>
  <settings defaultResolver="repsy"/>
  <credentials host="${this.repoHost()}"
               realm="Repsy"
               username="${this.username}"
               passwd="${password}"/>
  <resolvers>
    <ibiblio name="repsy" m2compatible="true" root="${environment.repoBaseUrl}/${this.repoName}/"/>
  </resolvers>
</ivysettings>
\`\`\`

Ivy looks credentials up by host and realm, so set \`realm="Repsy"\` as shown,
without it Ivy sends no credentials and Repsy refuses the request.
${tokenNote}
To publish, list a POM next to the jar in your \`ivy.xml\` (a version shows up in Repsy only when its POM is
uploaded);

\`\`\`xml
<ivy-module version="2.0">
  <info organisation="com.example" module="my-lib" revision="1.0.0"/>
  <configurations>
    <conf name="default"/>
  </configurations>
  <publications>
    <artifact name="my-lib" type="jar" ext="jar" conf="default"/>
    <artifact name="my-lib" type="pom" ext="pom" conf="default"/>
  </publications>
</ivy-module>
\`\`\`

Then build that POM with \`ivy:makepom\` and publish with \`publishivy="false"\`, otherwise Ivy also uploads its own
ivy file as \`ivy-<revision>.xml\` and Repsy refuses it with \`400 invalidArtifactPath\` (the jar and the POM are
already stored by then, but the build fails). Ivy's \`overwrite\` defaults to \`false\`, so Ivy itself refuses to
publish over a file that already exists ("destination file exists and overwrite == false"): set
\`overwrite="true"\` on \`ivy:publish\` to republish a SNAPSHOT, while Repsy's *Allow override* setting still
decides for a release;

\`\`\`xml
<project name="my-lib" xmlns:ivy="antlib:org.apache.ivy.ant">
  <target name="publish">
    <ivy:settings file="ivysettings.xml"/>
    <ivy:resolve file="ivy.xml"/>
    <ivy:makepom ivyfile="ivy.xml" pomfile="build/my-lib.pom"/>
    <ivy:publish resolver="repsy" pubrevision="1.0.0" publishivy="false">
      <artifacts pattern="build/[artifact].[ext]"/>
    </ivy:publish>
  </target>
</project>
\`\`\`

If your module has dependencies, give \`ivy:makepom\` a \`<mapping conf="default" scope="compile"/>\`, otherwise every
dependency is written to the POM as optional and a consumer does not resolve it transitively.

Ivy uploads no \`maven-metadata.xml\`, so Repsy answers a request for the artifact-level one from the versions it has
registered (a \`maven-metadata.xml\` a client did upload is served as it is). Maven \`LATEST\` and version ranges, and
Gradle, sbt and Ivy dynamic versions, then resolve an artifact published this way.
`;
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
