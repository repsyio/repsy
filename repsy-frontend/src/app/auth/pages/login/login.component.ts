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

import { CommonModule, NgOptimizedImage } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, ViewEncapsulation } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';

import { environment } from '../../../../environments/environment';
import { ToastService } from '../../../panel/shared/components/toast/toast.service';
import { AuthService } from '../service/auth.service';
import { LoginForm } from './form/login-form';

@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css'],
  imports: [CommonModule, ReactiveFormsModule, RouterModule, NgOptimizedImage],
  changeDetection: ChangeDetectionStrategy.OnPush,
  encapsulation: ViewEncapsulation.None,
})
export class LoginComponent implements OnInit {
  public form: FormGroup;
  public inputType = 'password';
  public visible = false;
  public loading = false;

  public images: string[] = ['hipopotam.png'];
  public randomImage = '';
  public apiBaseUrl: string;

  constructor(
    private readonly router: Router,
    private readonly fb: FormBuilder,
    private readonly authService: AuthService,
    private readonly toastService: ToastService,
  ) {}

  public ngOnInit(): void {
    this.apiBaseUrl = environment.apiBaseUrl;

    this.setRandomImage();
    this.form = this.fb.group({
      username: [
        '',
        [
          Validators.required,
          Validators.minLength(3),
          Validators.maxLength(150),
          Validators.pattern(/^[a-zA-Z0-9@_\-.]+$/),
        ],
      ],
      password: [
        '',
        [
          Validators.required,
          Validators.minLength(6),
          Validators.maxLength(50),
          Validators.pattern(/^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=\S+$).+$/),
        ],
      ],
    });
  }

  public login(): void {
    this.loading = true;
    this.form.disable();

    const form = Object.assign(new LoginForm(), this.form.value);

    this.authService
      .logIn(form)
      .then(() => {
        this.loginAndRedirectPanel(form);
      })
      .catch((err: string) => this.toastService.show(err, 'error'))
      .finally(() => {
        this.loading = false;
        this.form.enable();
      });
  }

  public toggleVisibility(): void {
    if (this.visible) {
      this.inputType = 'password';
      this.visible = false;
    } else {
      this.inputType = 'text';
      this.visible = true;
    }
  }

  public setRandomImage() {
    const randomIndex = Math.floor(Math.random() * this.images.length);
    this.randomImage = `/assets/images/${this.images[randomIndex]}`;
  }

  private loginAndRedirectPanel(form: LoginForm) {
    this.authService
      .logIn(form)
      .then(() => {
        this.router.navigateByUrl('/');
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.loading = false;
        this.form.enable();
      });
  }
}
