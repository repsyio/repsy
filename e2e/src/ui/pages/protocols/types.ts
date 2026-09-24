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

/**
 * The shape of a per-protocol descriptor (RPS-1255). The panel renders the same seven-ish pages for
 * every package format (list, optional grouping level, versions, docker manifests, version detail),
 * all tagged with the protocol-neutral `pkg-*` ids, so ONE parametrised page object
 * (`../protocol.ts`) plus ONE scenario template (RPS-1256's `registerPackageScenarios`) can drive
 * all nine. What differs between protocols is DATA, and it lives here: routes, row keys, search
 * placeholders, sort options, delete dialogs, install snippets, and the gaps (a level with no search,
 * no pagination or no mobile card list) so the template never special-cases a protocol.
 *
 * Every value was read from the Angular templates and components under
 * `repsy-frontend/src/app/panel/pages/repository/<proto>/` and the `data-testid` inventory
 * (`git grep -n data-testid repsy-frontend/src/app`). Where a value is only read from code and has
 * not been exercised in a browser yet, the descriptor says "unverified" in a comment; the story that
 * first runs that protocol (RPS-1256 for maven/npm/docker/pypi, RPS-1257 for the rest) confirms it
 * and fixes the descriptor in its own `<proto>.ts`.
 */
import type { PackageProtocol, PackageRef } from '../../../seed/packages.js';

/** The pages of one protocol. `sublist` exists for maven and npm, `manifests` for docker only. */
export type LevelName = 'list' | 'sublist' | 'versions' | 'manifests' | 'detail';
export type ListLevelName = Exclude<LevelName, 'detail'>;

/** A delete affordance: the danger-modal title and the success toast that follows a confirm. */
export interface DeleteAffordance {
  dialogTitle: string;
  successToast: string;
}

/** The route of a page (a path plus any query string) for a package; the list level ignores `target`. */
export type PathBuilder = (repo: string, target?: PackageRef) => string;

/**
 * One list-like page: the package list, maven's group/artifact list, npm's scope list, a versions
 * list, docker's tag list and manifest list. Its testids are `<prefix>-table`, `<prefix>-row-<key>`,
 * `<prefix>-cards`, `<prefix>-card-<key>` with `prefix` = `pkg-<level>`.
 */
export interface ListLevel {
  path: PathBuilder;
  /** The raw identity a row of `target` is keyed by at this level (`@scope/name`, `group:artifact`, ...). */
  rowKey: (target: PackageRef) => string;
  /**
   * The search box (`pkg-search` > `search-input`); null = the page has none. `placeholder` is what it
   * says, `term` what to type to find `target`'s row: it is NOT always the row key (probed: maven's
   * group list searches the GROUP only, npm's list searches the SCOPE only and takes it with or without
   * the `@`; typing a full `group:artifact` or `@scope/name` matches nothing).
   */
  search: { placeholder: string; term: (target: PackageRef) => string } | null;
  /** The sort option names in menu order (ids are `sort-option-<name>`); the first is the default. Null = no sort. */
  sort: readonly string[] | null;
  /** Whether the page has a pager at all (`pagination` is otherwise absent from the DOM). */
  pagination: boolean;
  /** Whether the mobile card list exists (`<prefix>-cards`); false for cargo and NuGet version lists (UX-12). */
  mobileCards: boolean;
  /** The row dropdown's Delete (managers only); null = rows cannot be deleted from this page. */
  rowDelete: DeleteAffordance | null;
  /** Where a click on the row itself goes; null = the row is not clickable. */
  rowOpens: LevelName | null;
  /** In-row links to other levels, by the in-row testid (`row-package-link` -> the versions page, ...). */
  rowLinks: Partial<Record<LevelName, string>>;
  /** Docker's install bar above the tag and manifest lists (`pkg-install-snippet`). */
  installBar: boolean;
  /**
   * RPS-1256: a package name that lands on the SAME page of this level as `target` (maven: the same
   * group, npm: the same scope), `n` telling siblings apart. Only the `sublist` level needs it: it
   * lets the shared delete scenario seed two artifacts of one group and delete one. Absent = the
   * template does not run a sublist delete for this level.
   */
  siblingName?: (target: PackageRef, n: number) => string;
}

/** The version detail page (docker: the tag detail). Every detail page has exactly one `pkg-detail-install`. */
export interface DetailLevel {
  path: PathBuilder;
  /**
   * Substrings the primary install snippet (`pkg-detail-install-text`) contains for `target` in
   * `repoName`. Never the repo URL: that is `repoUrlIn`'s job (the host comes from the stack).
   */
  installContains: (repoName: string, target: PackageRef) => readonly string[];
  /**
   * Where the stack's repo URL shows on the detail page: in the primary `install` text, in the
   * `snippet:<slug>` block of that name, or `none` (maven, npm and cargo show no URL there: their
   * URL lives in the Configure modal). A scenario asserting "the install snippet contains the repo
   * URL" must branch on this, as data, not on the protocol.
   */
  repoUrlIn: 'install' | 'none' | `snippet:${string}`;
  /** The element that holds the install text inside `pkg-detail-install-text`. */
  installTextElement: 'span' | 'code' | 'pre';
  /** `pkg-detail-snippet-<slug>` blocks besides the install one. */
  snippets: readonly string[];
  /** Protocol-specific `pkg-detail-*` ids beyond the common ones (name, version, published, metadata, delete, error). */
  extraIds: readonly string[];
  /** The page renders `data-testid="readme"`. */
  readme: boolean;
  /** The Delete button (managers only) and where the browser lands after a confirmed delete. */
  delete:
    | (DeleteAffordance & {
        landsOn: LevelName | 'unverified';
        /** Where deleting the package's LAST version lands, when that differs from `landsOn` (NuGet, Helm). */
        landsOnLast?: LevelName;
      })
    | null;
}

/**
 * The "Configure" modal (`pkg-configure` opens it on every list page; the settings page's token row
 * opens the same component in its deploy-token variant). RPS-1256 added it: the four first protocols
 * already differ (maven and PyPI say `YOUR_PASSWORD`, npm and Docker have no password placeholder at all).
 * A descriptor without one gets the template's default (`<label> Configuration`, `YOUR_PASSWORD`,
 * `YOUR_DEPLOY_TOKEN`), which RPS-1257 replaces per protocol as it runs them.
 */
export interface ConfigureModal {
  /** `config-modal-title` of the normal variant (`Maven Configuration`, `NPM Configuration`). */
  title: string;
  /** `config-modal-title` of the deploy-token variant (opened from a token row in the settings). */
  deployTokenTitle: string;
  /** Substrings the normal variant shows (always the repo name; its URL where the modal prints one). */
  contains: (repoName: string, repoUrl: string) => readonly string[];
  /** The password placeholder of the normal variant; absent = the modal has none (npm, docker). */
  passwordMarker?: string;
  /**
   * What the deploy-token variant says where the password would be (`YOUR_DEPLOY_TOKEN`). Absent = the
   * two variants have the SAME body and differ only by their title (Cargo, Ruby): nothing to tell apart.
   */
  deployTokenMarker?: string;
}

export interface ProtocolDescriptor {
  protocol: PackageProtocol;
  /** How the panel names the format in prose. */
  label: string;
  levels: {
    list: ListLevel;
    sublist?: ListLevel;
    versions: ListLevel;
    manifests?: ListLevel;
    detail: DetailLevel;
  };
  /** Toolbar buttons beyond the common `pkg-configure` / `pkg-settings` / `pkg-refresh`. */
  toolbar: { browseFiles: boolean };
  /**
   * Whether deleting a package's LAST version removes the package from the list (true for maven, npm,
   * pypi, nuget). Docker keeps the image listed (probed: an image with no tags stays, digest empty,
   * size 0 B). `unverified` = read from the code only.
   */
  lastVersionRemovesPackage: boolean | 'unverified';
  /** Routes that are not a level (maven's `/:repo/browser`). */
  extraPaths: Readonly<Record<string, (repo: string) => string>>;
  /** The Configure modal's texts (RPS-1256); absent = the template's default. */
  configure?: ConfigureModal;
  /** `SeedPackageOptions.variant` values the seeder accepts (helm: two backend modules); absent = one way. */
  seedVariants?: readonly string[];
}

/** Throws unless a route that needs a package was given one. */
export function need(target: PackageRef | undefined, what: string): PackageRef {
  if (!target) {
    throw new Error(
      `${what}: this page is for one package, so a target (name and version) is required`,
    );
  }
  return target;
}

/** The sort most lists offer: Newest (the default) and Oldest. */
export const NEWEST_OLDEST: readonly string[] = ['Newest', 'Oldest'];
/** Cargo, Helm and Ruby lists add Name sorts to Newest/Oldest. */
export const NEWEST_OLDEST_NAME: readonly string[] = [
  'Newest',
  'Oldest',
  'Name (A-Z)',
  'Name (Z-A)',
];
