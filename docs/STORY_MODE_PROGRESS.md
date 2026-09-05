# Aster Story Mode Progress

Branch: `feature/story-mode`

Baseline: `main@35f214d4808f529efad4a7430e488e67701fb754` — Aster 2.3.0 / versionCode 57.

## Milestones

- [x] M0 — repository audit and migration design
- [x] M1a — story data foundation
- [ ] M1b — story entry, workspaces, archive manual editing
- [ ] M2a — story context composition and budget
- [ ] M2b — background memory maintenance and proposals
- [ ] M3 — revision recovery and memory invalidation
- [ ] M4 — UI polish, full acceptance tests, signed test APK

## Current facts

- No `AGENTS.md` exists in the repository.
- Ordinary chats remain in existing SharedPreferences JSON storage and are not migrated into stories.
- Story persistence uses a separate `aster_story.db` SQLite database with explicit schema versioning and foreign keys.
- Logical story messages and message revisions are separate; only the active revision can be effective.
- Complete Prose revisions are memory-eligible; Discussion, interrupted and stopped revisions are not.
- Story persistence has transaction boundaries, timeline-global sequence numbers and durable job dedupe keys.
- Story text generation reuses `ApiRepository.streamChat`; no second HTTP stack is added.
- Existing API profile transfer is not a full app backup and does not include conversations or story archives.
- There is no current WorkManager/Room/DI framework; pending story jobs will be recovered by the story subsystem when it initializes.

## Validation

- M1a commit: `8cd5f76bbaf8768c54ce922ecb5d55ce8ead2639`.
- Android build run `33952584640` completed successfully, including `testDebugUnitTest` and signed Release assembly.

## Last saved point

M0 report: `docs/STORY_MODE_M0.md`.
M1a data foundation is build-validated.

Current task: M1b — connect Story mode to the Aster shell, preserve Discussion/Prose drafts and scroll state independently, and expose manual Story Archive records. Do not change the stable app version yet.