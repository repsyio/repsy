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

import { Component, Input } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { CargoService } from '../../cargo/service/cargo.service';
import { DockerService } from '../../docker/service/docker.service';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { PypiService } from '../../pypi/service/pypi.service';
import {GolangService} from "../../golang/service/golang.service";

@Component({
  selector: 'app-delete-repo',
  templateUrl: './delete-repo.component.html',
  styleUrls: ['./delete-repo.component.css'],
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class DeleteRepoComponent {
  @Input() public activeRepository: RepoPermissionInfo;
  @Input() public repoType: string;

  public public: boolean;
  public loading: boolean;
  public visibilityForm: FormGroup;

  constructor(
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
    private readonly cargoService: CargoService,
    private readonly golangService: GolangService,
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly router: Router,
  ) {
    this.visibilityForm = new FormGroup({
      publicRepository: new FormControl(false, [Validators.required]),
    });
  }

  public deleteRepo() {
    const successMsg = 'Repository deleted successfully';
    this.dangerModalService.show('Delete Repository', 'Delete', () => {
      this.loading = true;

      this.deleteRepoService(this.activeRepository.repoName)
        .then(() => {
          this.router.navigate(['/repositories']).then(() => {
            this.toastService.show(successMsg, 'success');
          });
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        })
        .finally(() => {
          this.loading = false;
        });
    });
  }

  private deleteRepoService(repoName: string) {
    switch (this.repoType) {
      case RepoType.MAVEN:
        return this.mavenService.deleteRepository(repoName);
      case RepoType.NPM:
        return this.npmService.deleteRegistry(repoName);
      case RepoType.PYPI:
        return this.pypiService.deleteRepository(repoName);
      case RepoType.DOCKER:
        return this.dockerService.deleteRepository(repoName);
      case RepoType.CARGO:
        return this.cargoService.deleteRepository(repoName);
      case RepoType.GOLANG:
        return this.golangService.deleteRepository(repoName);
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
