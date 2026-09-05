# Aster Story Mode Progress

Branch: `feature/story-mode`

Baseline: `main@35f214d4808f529efad4a7430e488e67701fb754` — Aster 2.3.0 / versionCode 57.

## Milestones

- [x] M0 — repository audit and migration design
- [ ] M1a — story data foundation
- [ ] M1b — story entry, workspaces, archive manual editing
- [ ] M2a — story context composition and budget
- [ ] M2b — background memory maintenance and proposals
- [ ] M3 — revision recovery and memory invalidation
- [ ] M4 — UI polish, full acceptance tests, signed test APK

## Current facts

- No `AGENTS.md` exists in the repository.
- Ordinary chats remain in existing SharedPreferences JSON storage and are not migrated into stories.
- Story persistence will use a separate SQLite database with explicit schema migrations and transactions.
- Story text generation reuses `ApiRepository.streamChat`; no second HTTP stack will be added.
- Existing API profile transfer is not a full app backup and does not include conversations or future story archives.
- There is no current WorkManager/Room/DI/revision framework; those capabilities must not be assumed.

## Last saved point

M0 report: `docs/STORY_MODE_M0.md`.

Next task: M1a — implement domain ids, SQLite schema/repository, message revision boundary and foundation tests. Do not change the stable app version yet.