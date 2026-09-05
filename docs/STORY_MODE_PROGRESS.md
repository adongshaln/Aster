# Aster Story Mode Progress

Branch: `feature/story-mode`

Baseline: `main@35f214d4808f529efad4a7430e488e67701fb754` — Aster 2.3.0 / versionCode 57.

## Milestones

- [x] M0 — repository audit and migration design
- [x] M1a — story data foundation
- [x] M1b — story entry, workspaces, archive manual editing shell
- [x] M2a — story context composition and budget
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
- Story Discussion and Prose have independent drafts, scroll anchors, message histories and generation jobs.
- Workspace saves use strictly monotonic state versions; stale asynchronous writes are rejected by SQLite persistence.
- Manual stop removes an empty assistant placeholder but preserves non-empty partial output as `stopped`.
- Story context has a hard final-request character ceiling. An oversized current input fails before the network request rather than producing an over-budget payload.
- Pinned confirmed material is mandatory: its section cap is only a planning target. Pinned facts may use additional global budget and fail explicitly if all pinned facts plus the current request cannot fit.
- Optional confirmed material is ranked by basic character/place relevance using canonical names and aliases attached from active entities.
- Recent history is selected only as a continuous suffix of complete user/assistant rounds; an oversized newer round blocks older short turns from leapfrogging it.
- Prose context receives active confirmed material and complete Prose history only. Pending proposals, inference-only memory and Discussion history are excluded.
- Discussion context may receive pending candidates/inferences, but they are explicitly marked non-authoritative and are never promoted to Prose context by the composer.
- Existing API profile transfer is not a full app backup and does not include conversations or story archives.
- There is no current WorkManager/Room/DI framework; pending story jobs will be recovered by the story subsystem when it initializes.

## Validation

- M1a commit: `8cd5f76bbaf8768c54ce922ecb5d55ce8ead2639`; Android build #71 passed.
- M1b stabilization commit: `784e3dcbf696a4168251ecbc6e06f41c4e2447dd`; Android build #80 passed, including unit tests, Release compile and signed APK staging.
- M2a base commit: `9e9a25249752c27954eec1f791c8a4ecf6a6e40a`; Android build #86 passed.
- M2a closeout adds hard-budget rejection, mandatory pinned-memory behavior, complete-round history truncation and basic character/alias/place relevance selection. CI must pass before M2b begins.

## M2b hard prerequisite

Before any automatic memory change set can be committed, **manual archive mutations must first gain the same consistency boundary**:

1. add/update/pin/deactivate manual records must write an explicit durable change log;
2. the mutation and its log entry must atomically advance the story's `memoryVersion`;
3. automatic jobs must snapshot and compare that version before commit.

Until that prerequisite is implemented, current manual `deactivateRecord` is only a soft deactivation/hide operation. It is **not** a complete undo/revert capability.

## Last saved point

M0 report: `docs/STORY_MODE_M0.md`.
M1b runtime stabilization: `docs/STORY_MODE_FIX72.md`.

Current task: finish M2a validation, then enter M2b by implementing manual archive mutation log + atomic `memoryVersion` transaction before any automatic organizer commit path. Do not change the stable app version yet.
