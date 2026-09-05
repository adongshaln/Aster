# Aster Story Mode — M0 repository audit

Status: completed against `main` baseline `35f214d4808f529efad4a7430e488e67701fb754`.

Baseline app version: **2.3.0**, `versionCode 57`, package/application id `com.adong.adchat`.

The repository does not contain `AGENTS.md`; repository root search also found no AGENTS rules file. This audit therefore treats current source, README and CI configuration as the implementation facts.

## 1. Current architecture map

### App shell and navigation

- `app/src/main/java/com/adong/adchat/MainActivity.kt`
  - `AsterApp` owns the top-level `AppPage` enum: Chat / Draw / Media / Settings.
  - A single `MainViewModel` is activity-scoped and passed to the major pages.
  - The drawer owns ordinary conversation creation, search, selection, rename and delete.
  - `SnackbarHost` is an app-level transient notice surface.
- `app/src/main/java/com/adong/adchat/ui/screens/ChatScreen.kt`
  - Owns ordinary chat scroll state, keyboard-follow behavior, reading veil, question navigation, message rendering and composer.
  - `ChatMessageItem` and `ChatComposer` are currently implemented inside this screen rather than as a reusable story/chat surface.
- `app/src/main/java/com/adong/adchat/ui/components/*`
  - Reusable Aster sheets/dialogs/design primitives and quick model switching.
- `app/src/main/java/com/adong/adchat/ui/theme/*`
  - Existing warm Canvas / Ink / Accent visual system.

### Configuration and API profiles

- `app/src/main/java/com/adong/adchat/data/Models.kt`
  - `ApiProfile`, `AppConfig`, `ConfigStore`.
  - Profiles hold Chat and Responses paths, model ids, reasoning/cache/tool options and encrypted API keys.
  - `ConfigStore` uses `SharedPreferences("adchat_api_config")` and Android Keystore AES-GCM for API key persistence.
- `app/src/main/java/com/adong/adchat/data/ChatApiPolicy.kt`
  - GPT model ids are forced to Responses; otherwise the saved `chatApiMode` selects Chat vs Responses.
- `app/src/main/java/com/adong/adchat/data/ConversationRoute.kt`
  - A normal conversation stores `profileId` and `model`; legacy conversations can infer a route from earlier assistant metadata.

### Network request path

- `app/src/main/java/com/adong/adchat/data/ApiRepository.kt`
  - `streamChat(...)` is the reusable text generation entry point.
  - It dispatches to `streamChatCompletions(...)` or `streamResponses(...)` using `profile.usesResponses(model)`.
  - Chat Completions accepts ordinary system + role messages and detects completion through `[DONE]`, `finish_reason`, or terminal usage events.
  - Responses detects `response.completed`; `response.failed` / `response.incomplete` are failures.
  - Both use SSE and return `ChatCompletionResult` with final text/usage/citations/files/tool activities.
  - Before the first delta, retryable transport failure can retry once; after partial output, safe resume is supported when enabled and no tools are active.
  - Cancellation propagates into the OkHttp call.

Story mode should reuse `ApiRepository.streamChat` and existing profile/model routing. Do **not** implement a second HTTP stack. A story request will construct its own context as `ChatMessage` input and supply story-specific system/instruction text. The memory organizer can call the same stream endpoint while collecting its output without presenting deltas in the prose UI.

### Ordinary chat state and persistence

- `app/src/main/java/com/adong/adchat/data/Models.kt`
  - `ChatMessage` is a flat immutable message object with one id and no revision/branch identity.
  - `Conversation` is a flat list of `ChatMessage`, plus profile/model route.
- `app/src/main/java/com/adong/adchat/data/ConversationStore.kt`
  - Ordinary conversations are stored as JSON in `SharedPreferences("adchat_conversations")`, key `conversations_v1`.
  - Up to 100 conversations are serialized as one JSON array.
  - A second `SharedPreferences("adchat_stream_recovery")` key stores one active stream recovery snapshot.
  - Streaming messages are normally excluded from committed conversation JSON; recovery snapshots intentionally persist interrupted partial output.
- `app/src/main/java/com/adong/adchat/data/ChatSessionStore.kt`
  - Per-conversation ordinary chat drafts and last active conversation are stored in `SharedPreferences("adchat_chat_session")`.
  - Maximum 100 drafts, 30,000 chars each.

### Stream completion, stop and retry in `MainViewModel`

- `sendMessage()` immediately persists the user message, then appends a streaming assistant placeholder.
- During generation it periodically updates Compose state and writes paid partial-output recovery snapshots.
- Normal completion replaces the placeholder with final result metadata, marks it non-streaming and persists the conversation.
- Manual stop keeps partial text as `isStopped=true` (or removes a blank assistant placeholder).
- Transport failure after text keeps the partial text as `isInterrupted=true`.
- An incomplete/interrupted/stopped message is excluded by `ApiRepository` from normal context.
- `retryMessage()` for interrupted/stopped output appends a new user instruction asking the model to continue; this is continuation, not a revision model.

### Existing message revision / branching behavior

There is **no general message revision or branch model** in 2.3.0.

`regenerateMessage()` only permits the final completed assistant message. It removes the final assistant message and its preceding user message, persists, then resends the same prompt. The replaced reply is not retained as a selectable revision. Editing an older user message is not implemented. Story mode therefore cannot map its memory validity to ordinary `ChatMessage.id` alone.

### Existing configuration transfer / backup behavior

Settings "配置管理" only imports/exports API profiles using format `adchat-profiles-v1`. It does **not** export conversations, media, or an application-wide backup. Story data can therefore initially remain outside profile transfer without silently breaking an existing full-backup promise. Story UI/documentation must still state that story archive export is not part of the first implementation if it remains unavailable.

### Background work and dependency injection

- `MainViewModel` directly constructs `ConfigStore`, `ApiRepository`, `ConversationStore`, `ChatSessionStore`, and `ArtworkStore`.
- There is no DI container.
- `app/build.gradle.kts` contains no Room, WorkManager, or KSP/KAPT persistence stack.
- `AndroidManifest.xml` registers only `MainActivity` and a `FileProvider`; there is no worker/service for story jobs.
- Current long-running work is Coroutine/`viewModelScope` based.

For story memory v1, pending jobs must be persisted and resumed when the story subsystem initializes. We will not claim processing continues after a user force-stops the app. A platform scheduler can be added later if requirements expand.

## 2. Build, signing and test baseline

- `app/build.gradle.kts`: Android SDK 36, Java 17, Kotlin/Compose, current version 2.3.0 / 57.
- Release signing continues to use `keystore.properties`; no new key will be created.
- `.github/workflows/android-build.yml` runs on every branch push, executes `testDebugUnitTest assembleRelease`, injects the four existing ASTER signing secrets when present, verifies the signed APK, and uploads a 14-day artifact.
- Main baseline build run `33947148710` completed successfully for SHA `35f214d...`.
- `.github/workflows/ui-preview.yml` only auto-runs for `design/**` branches; `feature/story-mode` will still receive normal Android build CI, while UI emulator runs must be manually dispatched or the trigger extended later.

## 3. M1 persistence decision

### Decision: separate story SQLite database using Android `SQLiteOpenHelper`

Story state is substantially more relational and transactional than the existing flat conversation JSON. Reusing the single `conversations_v1` JSON would make atomic memory change sets, idempotent jobs, revision invalidation and source queries fragile, and would rewrite very large story histories repeatedly.

For the first story implementation, use the platform SQLite API through a dedicated `StoryDatabase`/repository rather than adding Room/KSP during the foundation milestone. This provides:

- transactions for one atomic memory change set;
- unique indexes for job idempotency and stable ids;
- efficient source/version queries;
- no new Gradle annotation processor or code-generation risk;
- an explicit schema version and upgrade path.

The story DB is independent from `adchat_conversations`. Existing chats do not need conversion and remain ordinary chats by default.

### Proposed v1 schema boundaries

Exact Kotlin names may adjust during implementation, but the database needs these persisted concepts:

1. `stories`
   - id, title, profile id, model, created/updated time, current timeline, memory version, automatic-memory flag.
2. `timelines`
   - id, story id, parent timeline and fork revision nullable; first release creates one default timeline.
3. `story_messages`
   - stable logical message id, story/timeline, workspace (`discussion`/`prose`), role, sequence, active revision id.
4. `message_revisions`
   - revision id, logical message id, content, state (`complete`/`streaming`/`interrupted`/`stopped`/`superseded`), generation metadata, created time.
5. `story_entities`
   - stable entity id, kind, canonical name, aliases JSON, active flag.
6. `memory_records`
   - record id, kind, payload/content, subject/object entity ids when relevant, scope, epistemic nature, effective sequence, source revision, pinned/active flags.
7. `proposals`
   - candidate/plan record, source revision, decision source revision/user action, state (`pending`/`accepted`/`rejected`/`superseded`).
8. `memory_change_sets`
   - change id, story/timeline, base memory version, source revision, status, operations JSON, conflict JSON, committed version.
9. `memory_jobs`
   - job id, story/timeline/source revision, job kind, dedupe key, base version, status, attempts, error, created/updated time.
10. `story_snapshots`
    - story/timeline, sequence/version, compact snapshot JSON, log cursor.
11. `story_workspace_state`
    - per story/workspace draft plus last-known scroll anchor/index. This is session UI state, not story truth.

The DB schema should enforce foreign keys and use a transaction for change-set application. Model output must never contain trusted story/timeline/job ids; application-owned ids are bound outside the model response.

## 4. Data migration and compatibility plan

### Existing ordinary chats

No destructive migration. `ConversationStore`, `ChatSessionStore` and the normal Chat page remain unchanged. On upgrade, story tables start empty. Existing and imported profile configs remain usable as route references.

### API profile deletion

Stories reference a profile id/model snapshot. If a referenced API profile is later deleted, do not rewrite story history. Story generation should resolve to the saved profile when available and otherwise require the user to select a replacement route before the next request. This is safer than silently rerouting authored story history.

### Story DB migrations

- DB starts at schema version 1.
- Upgrades must be additive where possible.
- A migration failure must not erase `adchat_conversations` or profile configuration.
- Never use destructive database fallback for story history.

### Backup/export scope

The existing Settings "配置管理" remains API-profile transfer only. Do not rename it to imply a full backup. If story archive export is not delivered in v1, the Story Archive should say local story data is not included in API configuration export.

## 5. Reuse vs new implementation

### Reuse directly

- `ApiProfile` / `ConfigStore` and current model selection.
- `ApiRepository.streamChat` transport, SSE handling, safe pre-delta retry and cancellation.
- Aster color/typography/dialog/sheet components.
- Markdown/novel reading renderer and Aster thinking/streaming presentation where possible.
- fixed signing and Android build workflow.

### Reuse after extraction/adaptation

- `ChatMessageItem` rendering: expose a reusable message body surface for story workspaces.
- `ChatComposer`: make the existing composer configurable by state/callbacks rather than binding it only to `MainViewModel`.
- reading list behavior: story needs independent `LazyListState`/draft for Discussion and Prose.
- quick model switcher: Story should use its own saved profile/model route and not mutate an unrelated ordinary conversation when switching.

### Must be new

- Story domain/repository/database.
- Discussion vs prose workspace identity and per-workspace session state.
- Message logical ids + revisions and active-version rules.
- Memory records/proposals/change sets/jobs/snapshots.
- Story context composer and retrieval/budget policy.
- Story memory organizer prompt + parser + strict validator/reducer.
- Story archive UI and manual corrections.
- Revision invalidation/rebuild logic.

## 6. Request organization plan

### Prose request

Build the request in this order:

1. story creation rules;
2. pinned confirmed facts;
3. current scene/current state;
4. relevant character facts, knowledge and directed relationships;
5. relevant world facts and applicable author plans;
6. older summary;
7. recent complete active prose;
8. current user input.

Discussion-only proposals and rejected ideas are never inserted into prose context.

### Discussion request

Use a separate discussion system instruction and retrieve confirmed facts plus relevant candidates/recent discussion. If discussing a selected prose passage, include only that passage and necessary preceding context. Example prose produced in Discussion remains discussion data unless the user explicitly turns it into prose through a defined action.

### Memory organizer request

Input is a frozen application-built snapshot: workspace, source user/assistant revisions, selected existing memory, base memory version and output schema. Output contains only structured operations/conflicts/candidates. The application validates source revisions, ids, scopes, allowed operation kinds, pinned-record protection, base version and size before a single transaction commit.

## 7. Concurrency and lifecycle design

- Each generation captures `storyId`, `timelineId`, `workspace`, source input revision and route before launch; switching workspace cannot redirect its result.
- Discussion and prose should not share one mutable global "current response target".
- Memory jobs are serialized per timeline. The UI may send the next prose turn before organization finishes.
- Context composer must overlay completed but not-yet-organized active revisions on top of the last committed memory version, preventing the next turn from forgetting recent prose.
- When a job completes, verify its source revision is still active and its base version is current. Otherwise mark stale/requeue; never commit blindly.
- Cancellation/interrupted/stopped prose is not eligible for the normal complete-event memory organizer.

## 8. Main risks

1. **MainViewModel size and coupling** — it is already very large. Story logic should use a dedicated `StoryViewModel`/repository instead of expanding it further.
2. **No existing revision model** — story revisions cannot be represented by ordinary `ChatMessage.id`; M3 depends on getting this boundary correct in M1a.
3. **No existing database** — schema mistakes are expensive. Add schema tests/reducer tests before UI complexity.
4. **No durable worker stack** — first release can recover pending jobs at next subsystem initialization, but cannot promise force-stop background execution.
5. **Profile deletion / route drift** — never silently switch story provider after its profile disappears.
6. **Prompt injection from prose** — organizer consumes story text as data. Its parser accepts only schema operations and application-owned source bindings; dialogue such as "delete all memories" is never executed as authority.
7. **Context budget** — provider context limit is not currently modeled in `ApiProfile`; first release must use a conservative configurable character/token estimate and make truncation visible when pinned facts alone exceed budget.
8. **UI extraction regression** — composer/reading behavior was recently acceptance-tested in 2.3.0. Reuse by narrow extraction, with ordinary Chat smoke coverage to ensure keyboard, bottom veil and reading flow do not regress.

## 9. Expected implementation commits

1. `docs: record story mode implementation map` — this M0 report.
2. `feat: add story data foundation` — SQLite schema/repository/domain ids/revisions/basic tests.
3. `feat: add story workspaces` — Story entry, StoryViewModel, Discussion/Prose, per-workspace drafts, archive manual editing shell.
4. `feat: compose story context` — strict context builder, retrieval and budget tests.
5. `feat: maintain story memory` — organizer request/parser/validator/change-set transaction/proposals/job recovery.
6. `fix: align memory with revisions` — revision switching, invalidation, stale-job rejection, snapshot/replay.
7. `test: harden story mode acceptance cases` — automated A01–A20 coverage where feasible, UI smoke coverage and migration checks.
8. `docs: complete story mode test handoff` — progress/limitations/test APK status.

No version bump is made during implementation. A stable version is chosen only after the story-mode test APK passes user acceptance.