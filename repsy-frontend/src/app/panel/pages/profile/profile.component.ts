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

import { Component, OnInit } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterModule } from '@angular/router';

import { BreadcrumbComponent } from '../../shared/components/breadcrumb/breadcrumb.component';
import { AccountInfoComponent } from './account-info/account-info.component';
import { DeleteAccountComponent } from './delete-account/delete-account.component';

@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [ReactiveFormsModule, RouterModule, BreadcrumbComponent, AccountInfoComponent, DeleteAccountComponent],
  templateUrl: './profile.component.html',
})
export class ProfileComponent implements OnInit {
  form: FormGroup;
  username = '';

  constructor(private readonly fb: FormBuilder) {}

  ngOnInit(): void {
    // Load user data first
    this.username = localStorage.getItem('username') || '';

    this.form = this.fb.group({
      username: [this.username, Validators.required],
      fullName: ['', [Validators.maxLength(100)]],
      email: ['', [Validators.required, Validators.email, Validators.minLength(4), Validators.maxLength(150)]],
      address: ['', [Validators.maxLength(300)]],
      phone: ['', [Validators.maxLength(15)]],
    });
  }
}
