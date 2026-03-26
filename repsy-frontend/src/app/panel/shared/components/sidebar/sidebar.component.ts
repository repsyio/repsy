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
import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { Router, RouterLink, RouterModule } from '@angular/router';

import { AuthService } from '../../../../auth/pages/service/auth.service';
import { ProfileService } from '../../../pages/profile/service/profile.service';
import { SearchboxComponent } from '../searchbox/searchbox.component';

@Component({
  selector: 'app-sidebar',
  imports: [RouterModule, CommonModule, RouterLink, SearchboxComponent, NgOptimizedImage],
  templateUrl: './sidebar.component.html',
  styleUrl: './sidebar.component.css',
})
export class SidebarComponent implements OnInit {
  @Input() public isMobileMenuOpen = false;
  @Output() closeModal = new EventEmitter<Event>();

  public username: string;
  public isAdmin = false;

  constructor(
    private readonly authService: AuthService,
    private readonly profileService: ProfileService,
    private readonly router: Router,
  ) {}

  public ngOnInit(): void {
    this.username = localStorage.getItem('username');
    this.loadUserRole();
  }

  private loadUserRole(): void {
    this.profileService
      .get()
      .then((profile) => {
        this.isAdmin = profile.role === 'ADMIN';
      })
      .catch((error) => {
        console.error('Failed to load user role:', error);
        this.isAdmin = false;
      });
  }

  public logOut(): void {
    this.authService.logOut();
    this.router.navigateByUrl('login');
  }

  closeMobileMenu(): void {
    this.closeModal.emit();
  }

  isActive(): boolean {
    const segments = this.router.url.split('/');
    const valid = ['maven', 'npm', 'docker', 'pypi'];

    return valid.includes(segments[1]) || this.router.url === '/repositories';
  }
}
