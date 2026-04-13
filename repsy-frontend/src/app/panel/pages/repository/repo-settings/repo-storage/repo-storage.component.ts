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
import { ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { RepoUsageInfo } from '../../../../shared/dto/repo-usage-info';
import { CargoService } from '../../cargo/service/cargo.service';
import { DockerService } from '../../docker/service/docker.service';
import { GolangService } from '../../golang/service/golang.service';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { PypiService } from '../../pypi/service/pypi.service';

@Component({
  selector: 'app-repo-storage',
  templateUrl: './repo-storage.component.html',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class RepoStorageComponent implements OnInit {
  @Input() public repoType: string;
  @Output() public fetch = new EventEmitter<void>();

  public usage: RepoUsageInfo;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly cargoService: CargoService,
    private readonly golangService: GolangService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.fetchRepoUsage();
  }

  repoUsageService() {
    switch (this.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.getRepoUsage();
      case RepoType.NPM:
        return this.npmService.fetchRegistryUsage();
      case RepoType.PYPI:
        return this.pypiService.fetchRepositoryUsage();
      case RepoType.DOCKER:
        return this.dockerService.fetchRepositoryUsage();
      case RepoType.CARGO:
        return this.cargoService.fetchRepositoryUsage();
      case RepoType.GOLANG:
        return this.golangService.fetchRepositoryUsage();
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  fetchRepoUsage() {
    this.repoUsageService()
      .then((data) => {
        this.usage = data;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      });
  }
}
