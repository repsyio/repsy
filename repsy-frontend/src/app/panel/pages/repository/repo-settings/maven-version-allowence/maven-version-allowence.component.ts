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

import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ProtocolRepoControllerService, RepoSettingsForm } from '../../../../../../generated/api';
import { SelectorComponent } from '../../../../shared/components/selector/selector.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoSupport, RepoType } from '../../../../shared/dto/repo/repo-type';
import { MavenService } from '../../maven/service/maven.service';

@Component({
  selector: 'app-maven-version-allowence',
  templateUrl: './maven-version-allowence.component.html',
  styleUrls: ['./maven-version-allowence.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, SelectorComponent, RouterLink],
})
export class VersionAllowanceComponent implements OnInit {
  @Input() public parentForm: FormGroup;
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Output() public fetch = new EventEmitter<void>();

  public selectedOption: RepoSupport = RepoSupport.ALL;
  public repoOptions: RepoSupport[] = [];

  private readonly MAVEN_OPTIONS = [RepoSupport.ALL, RepoSupport.SNAPSHOTS, RepoSupport.RELEASES];
  private readonly NUGET_OPTIONS = [RepoSupport.ALL, RepoSupport.PRE_RELEASE, RepoSupport.STABLE];

  constructor(
    private readonly mavenService: MavenService,
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.repoOptions = this.repoType === RepoType.NUGET ? this.NUGET_OPTIONS : this.MAVEN_OPTIONS;

    const snapshots = this.parentForm.get('snapshots')!.value;
    const releases = this.parentForm.get('releases')!.value;
    this.selectedOption = this.resolveSelectedOption(snapshots, releases);
  }

  public selectType(option: string) {
    const snapshots =
      option === RepoSupport.SNAPSHOTS || option === RepoSupport.PRE_RELEASE || option === RepoSupport.ALL;
    const releases = option === RepoSupport.RELEASES || option === RepoSupport.STABLE || option === RepoSupport.ALL;

    const form: RepoSettingsForm = {};
    form.privateRepo = this.parentForm.get('privateRepository')!.value;
    form.allowOverride = this.parentForm.get('allowOverride')!.value;
    form.snapshots = snapshots;
    form.releases = releases;

    if (this.repoType === RepoType.NUGET) {
      this.protocolRepoControllerService.updateSettings(this.repoName, form).subscribe({
        next: () => {
          this.fetch.emit();
          this.toastService.show(`Version allowance has changed to ${option}`, 'success');
        },
        error: () => {},
      });
    } else {
      this.mavenService.updateRepoSettings(form).subscribe({
        next: () => {
          this.fetch.emit();
          this.toastService.show(`Version allowance has changed to ${option}`, 'success');
        },
        error: () => {},
      });
    }
  }

  private resolveSelectedOption(snapshots: boolean, releases: boolean): RepoSupport {
    if (this.repoType === RepoType.NUGET) {
      if (snapshots && !releases) {return RepoSupport.PRE_RELEASE;}
      if (!snapshots && releases) {return RepoSupport.STABLE;}
      return RepoSupport.ALL;
    }

    if (snapshots && !releases) {return RepoSupport.SNAPSHOTS;}
    if (!snapshots && releases) {return RepoSupport.RELEASES;}
    return RepoSupport.ALL;
  }
}
