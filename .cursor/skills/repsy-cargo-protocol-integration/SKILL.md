---
name: repsy-cargo-protocol-integration
description: Analyze Repsy Cargo backend, protocol, and frontend modules to derive existing conventions, then guide adding Cargo support to repsy-frontend in parity with Maven, npm, PyPI, and Docker. Use when user mentions cargo integration, crates registry support, or Cargo-related changes in repsy-backend, repsy-protocols/cargo, or repsy-frontend.
---

# Repsy Cargo Protocol Integration

## Purpose

Use this skill to produce a convention-first implementation plan for Cargo support in `repsy-frontend`, based on existing Repsy patterns in:

- `repsy-backend/.../server/protocols/cargo`
- `repsy-protocols/cargo`
- `repsy-frontend`

This skill is guidance-only: it prioritizes analysis, pattern extraction, and actionable implementation steps.

## Non-Negotiable Rules

1. Do not invent architecture or naming patterns.
2. Derive conventions from existing Maven, npm, PyPI, and Docker implementations in the same repository.
3. Treat existing code as source of truth for:
   - UI architecture and routing patterns
   - API contract shapes and DTO naming
   - Error handling and state management
   - Component structure, shared utilities, and style usage
4. Keep parity behavior: Cargo should feel like a first-class protocol peer, not a special case.

## Trigger Conditions

Apply this skill when requests include one or more of:

- "cargo", "crate", "crates", "rust package"
- "add cargo to frontend"
- `repsy-backend` cargo protocol paths
- `repsy-protocols/cargo`
- protocol parity with Maven, npm, PyPI, Docker

## Workflow

Copy this checklist and track progress:

```markdown
Cargo Integration Progress:
- [ ] Step 1: Map existing protocol features in frontend
- [ ] Step 2: Inspect Cargo backend/protocol capabilities
- [ ] Step 3: Extract hard conventions from current code
- [ ] Step 4: Produce parity matrix (Maven/npm/PyPI/Docker vs Cargo)
- [ ] Step 5: Define minimal frontend change set
- [ ] Step 6: Validate against conventions and edge cases
```

### Step 1: Map existing protocol features in frontend

Identify where Maven, npm, PyPI, and Docker are represented in `repsy-frontend`:

- navigation/menu items
- protocol listing cards/tables
- details pages and protocol-specific tabs
- setup/help/install-command sections
- icons, labels, protocol constants, and routing keys
- API clients and request/response models

Outcome: a list of concrete reusable patterns and insertion points for Cargo.

### Step 2: Inspect Cargo backend/protocol capabilities

Inspect Cargo-related behavior from:

- `repsy-backend/.../server/protocols/cargo`
- `repsy-protocols/cargo`

Capture only frontend-relevant capabilities:

- auth requirements
- endpoints and method semantics
- metadata available for UI
- download/config/me/yank operations
- protocol-specific limitations

Outcome: capability summary that can be surfaced in frontend UX.

### Step 3: Extract hard conventions from current code

Derive conventions directly from existing frontend protocol implementations:

- naming conventions (files, symbols, constants, routes)
- component composition and folder structure
- reactive/data-fetching patterns
- i18n/message key patterns
- form style and validation behavior
- loading, empty, and error states

Outcome: explicit "must-follow" convention set with examples from existing code.

### Step 4: Build parity matrix

Create a matrix with rows for key protocol UX features and columns:

- Maven
- npm
- PyPI
- Docker
- Cargo (current/target)

Mark each row as:

- already available
- missing in Cargo frontend
- intentionally not applicable (with reason)

Outcome: scope boundary for implementation.

### Step 5: Define minimal frontend change set

Propose smallest coherent set of changes to make Cargo visible and usable like other protocols:

- protocol registration/constants
- menu/navigation exposure
- route additions
- protocol detail/config/install surfaces
- API integration and typed models
- icon/label/i18n assets
- tests (unit/component/integration equivalents used in project)

Keep plan file-oriented and ordered for implementation.

### Step 6: Validate quality gates

Before implementation is considered ready:

- no deviation from discovered conventions
- no duplicated logic when shared abstractions exist
- Cargo behavior aligned with backend/protocol semantics
- error/loading/empty states consistent with peer protocols
- all introduced strings localized using project pattern
- tests mirror existing protocol test style

## Output Format

When responding, use this format:

```markdown
## Cargo Frontend Integration Plan

### 1) Existing Pattern Baseline
- ...

### 2) Cargo Capability Summary (Backend/Protocol)
- ...

### 3) Convention Rules to Follow
- ...

### 4) Parity Matrix
| Feature | Maven | npm | PyPI | Docker | Cargo |
|---|---|---|---|---|---|
| ... | ... | ... | ... | ... | ... |

### 5) Proposed Frontend Changes
- `path/to/file`: reason and change summary

### 6) Validation Checklist
- [ ] ...
```

## Constraints

- Do not claim parity without checking existing protocol implementations.
- Do not reference assumptions as facts; mark unknowns explicitly.
- Prefer consistency with current code over generalized best practices.
- If Cargo backend capability is missing, propose frontend behavior that degrades safely and clearly communicates limitation.
