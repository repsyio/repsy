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

import { NgOptimizedImage } from '@angular/common';
import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  standalone: true,
  imports: [RouterLink, NgOptimizedImage],
  templateUrl: './not-found.component.html',
})
export class NotFoundComponent implements OnInit {
  public message = 'The page you are looking for does not exist.';
  public showBackButton = true;

  constructor(private readonly route: ActivatedRoute) {}

  public ngOnInit(): void {
    // Check if we have a custom message from route data
    this.route.data.subscribe((data) => {
      if (data['message']) {
        this.message = data['message'];
      }
      if (data['showBackButton'] !== undefined) {
        this.showBackButton = data['showBackButton'];
      }
    });
  }
}
