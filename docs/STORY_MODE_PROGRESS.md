# Aster Story Mode Progress

Branch: `feature/story-mode`

Baseline: `main@35f214d4808f529efad4a7430e488e67701fb754` — Aster 2.3.0 / versionCode 57.

## Current acceptance status (2026-09-06)

Code checkpoint `7ba25bd0343962b8ae8d8e3b27ddfe257475d32e` passed Android Build #100, including tests, Release compilation and fixed-signature APK upload. Application remains 2.3.0 / versionCode 57.

[Acceptance and design-gap review](STORY_MODE_ACCEPTANCE.md) is the current status authority; the sections below also retain historical checkpoint notes. [Original implementation plan](STORY_MODE_IMPLEMENTATION_PLAN.md) is preserved for comparison.

The full first release is **not ready**: structured mutable state/directed relationships/character knowledge, conflict handling, long-story summary coverage and discussion-to-model-rewrite workflows remain incomplete. CI success does not close these product gaps. No real-provider or on-device acceptance is claimed. This review changes documentation only.

## Milestones

- [x] M0 — repository audit and migration design
- [x] M1a — story data foundation
- [x] M1b — story entry, workspaces, archive manual editing shell
- [x] M2a — story context composition and budget
- [ ] M2b full design — conservative append-only jobs/proposals are implemented and tested; structured semantic maintenance/conflicts remain
- [ ] M3 — revision recovery and memory invalidation
- [ ] M4 — UI polish, full acceptance tests, signed test APK

## Current facts

- No `AGENTS.md` exists in the repository.
- Ordinary chats remain in existing SharedPreferences JSON storage and are not migrated into stories.
- Story persistence uses a separate `aster_story.db` SQLite database with explicit schema versioning and foreign keys.
- Logical story messages and message revisions are separate; only the active revision can be effective.
- Complete Prose revisions are memory-eligible; Discussion, interrupted and stopped revisions are not.
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
- Manual archive add/update/pin/deactivate uses one SQLite transaction for the record mutation, durable before/after audit entry and `stories.memory_version` increment.
- Manual no-op update/pin operations do not create a log entry or consume a memory version.
- Manual deactivation remains only a soft deactivate/hide operation; the audit trail is not a complete undo/replay mechanism.
- Completed active Prose replies can enqueue durable `organize_prose` jobs. Running jobs are reset to pending when the story subsystem initializes after process interruption.
- Organizer model output is strict append-only structured data: application-owned IDs are never accepted from the model; automatic confirmed memory is `prose_occurred`, never pinned, and automatic author plans remain pending proposals instead of Prose facts.
- Organizer jobs snapshot `memoryVersion` before model work and validate it again at commit. A newer manual/automatic memory commit makes the job stale and requeues it against the new version rather than overwriting newer state.
- Automatic memory additions, change-set audit and `memoryVersion` advancement commit in one SQLite transaction. Pending proposals are isolated from Prose context and are visible only through the Discussion candidate path.
- The first automatic organizer is conservative and append-only: it does not automatically update, deactivate or replace existing memory records.
- Existing API profile transfer is not a full app backup and does not include conversations or story archives.
- There is no current WorkManager/Room/DI framework; persistent organizer jobs resume when the story subsystem initializes, but force-stop background execution is not promised.

## Validation

- M1a commit: `8cd5f76bbaf8768c54ce922ecb5d55ce8ead2639`; Android build #71 passed.
- M1b stabilization commit: `784e3dcbf696a4168251ecbc6e06f41c4e2447dd`; Android build #80 passed, including unit tests, Release compile and signed APK staging.
- M2a base commit: `9e9a25249752c27954eec1f791c8a4ecf6a6e40a`; Android build #86 passed.
- M2a closeout commit: `6c0faef2c1495f1f3d12dff4cc5bd94504bd1f7c`; Android build #88 passed.
- M2b manual consistency commit: `88e202eac296af3f7bc3343ca6834ef61c77cae4`; Android build #89 passed, including unit tests, Release compile and signed APK staging.
- M2b automatic organizer adds strict output validation, durable/recoverable jobs, version-stale requeue, append-only memory/proposal commit and organizer regression tests. CI must pass before M3 begins.

## M2b consistency boundary

1. Every real manual add/update/pin/deactivate writes an explicit durable audit row.
2. Manual record mutation + audit row + `memoryVersion` increment are one SQLite transaction.
3. Automatic jobs bind to an active complete Prose source revision and snapshot the current `memoryVersion`.
4. Model output cannot supply trusted IDs or destructive operations; application code creates all record/proposal/change-set IDs.
5. Automatic commit revalidates the source revision and base version. Stale jobs do not commit; they requeue only while the source revision is still active.
6. Automatic memory + change set + version advancement commit atomically.

Current `deactivateRecord` is still only a soft deactivation/hide operation. It is **not** a complete undo/revert capability.

## Last saved point

M0 report: `docs/STORY_MODE_M0.md`.
M1b runtime stabilization: `docs/STORY_MODE_FIX72.md`.

Current next task: close the G1/G2 semantic-memory gaps documented in STORY_MODE_ACCEPTANCE.md, followed by long-story coverage and minimal product workflows. M3a–M3d checkpoint implementations are recorded below; full first-release acceptance is still open. Do not change the stable version yet.

## Organizer takeover checkpoint

The previously uncommitted tree `9f7a977c7ca47ad73362a8b2fd1356ba188d1a64`
was recovered intact and preserved as remote commit `e86028cd2fa432cbdd232fd5ec476b9c57c18bbd`.
Implementation commit `cdb45484104bc1b1330e4e9f9ec791e2f5731e3c` adds:

- separate Discussion candidate extraction; Discussion is rejected at the database boundary if it supplies prose facts;
- explicit candidate accept/reject in Archive → Changes; UI decisions and resulting confirmed records are transactional and logged;
- active source, current timeline and version validation in the commit transaction;
- completed-source dedupe independent of subsequent manual memory versions;
- a four-attempt automatic ceiling across stale/requeued jobs, explicit failed-job retry, and restart gap recovery;
- obsolete-source automatic memories/proposals excluded from archive/context queries;
- strict array/string output types, forbidden fields, and trailing-content rejection;
- real SQLite integration tests using Robolectric: rollback, idempotence, source replacement, version conflict, recovery, proposal decisions and retry bound.

Checkpoint `2bb110506783cae435e4f360e133f9be5dbdab82` passed GitHub Android build #92: unit tests, Release compilation and fixed-signature APK upload. This verifies the conservative M2b checkpoint, not full M1–M4 acceptance.

### Deliberate remaining limitations

This is a conservative M2b implementation checkpoint, not the finished M1–M4 release.
Natural-language acceptance is not inferred automatically: candidates require explicit UI adoption.
Changing state/relationship/knowledge extracts are stored as source-linked historical observations,
not an authoritative current-state register. Structured entity/attribute replacement, conflict review,
full source-linked change browsing, undo/replay and version switching remain to be completed before
claiming the full design. No real provider story was sent during these automated tests.

### Configuration wait fix

Missing API/model configuration now leaves organizer jobs pending without consuming attempts.
The scheduler does not immediately relaunch while configuration remains unavailable; selecting
a valid story route or explicitly retrying resumes pending work. A SQLite regression test covers
repeated blocked claims, persistence across reopening, and successful processing after restoration.
Configuration wait fix `a5ebadcea7bbd3dcdfc4b35169c5e3506e0f5012` passed Android Build #93 (tests, Release, fixed-signature APK).

### M3a — tail Prose revision checkpoint

- Tail assistant Prose can be manually revised and complete saved revisions restored through the message action.
- Revision completion states are preserved; the active pointer controls eligibility. Stopped/interrupted/legacy superseded revisions cannot be restored as complete.
- Pointer switch, memoryVersion advance, stale source jobs and before/after revision audit commit atomically.
- Source-linked records/proposals disappear from context when their revision is inactive; restoration reuses original records and preserves manual pin/deactivation decisions.
- Dedupe excludes obsolete sources so a new revision can independently record the same fact. Finalized revisions cannot be overwritten by streaming writes or physically deleted.
- Older messages with later content (including Discussion), streaming work and stale editor saves are rejected. This is deliberately tail-only until descendant invalidation and branching are implemented.
- This checkpoint does not implement model-driven rewrite, snapshots/replay, older chapter branching, or full M3 acceptance. Commit `b2d102afb5622191a004374bfa0084b1eca5771f` passed Android Build #94 (tests, Release and signed APK).

### M3b — checkpoint-based historical rewrite

- Before each new Prose assistant message, persist a transactionally consistent checkpoint of the active prefix, memories, proposals, entities and completed organizer sources. Immutable text is referenced by revision ID instead of duplicated in every checkpoint.
- “保留旧后续，从这里另写” creates an independent timeline from that checkpoint, remaps all source IDs and leaves the original continuation untouched. Later manual additions/edits are not copied backward.
- Historical routes can be restored. Workspace drafts/scroll positions are checkpointed on switching; late saves and old UI loads are scoped to their route. Route switches increment memoryVersion, rejecting obsolete organizer commits.
- Fork, inherited state, route switch and snapshot audit commit atomically. Reopening preserves routes and workspace state. No database schema or stable app version change is needed.
- Legacy chapters without a pre-generation checkpoint, changed prefixes, and snapshots containing parallel streaming output are rejected explicitly. Earlier inherited messages currently do not receive fabricated checkpoints.
- These are materialized checkpoint restores, not arbitrary manual-log undo/replay. Model-driven rewrite, full change browsing and full M3/M4 acceptance remain. This commit requires its own CI.

M3b follow-up: asynchronous UI loads now use a view-state epoch as well as route identity, preventing a late pre-switch refresh from moving the screen backward. Startup marks orphaned streaming revisions interrupted (never complete), so process death cannot permanently block historical-route recovery. Added a restart regression test.

### M3c — audit browsing and guarded manual undo

M3b checkpoint `b6b3daa5331344eeb81aa4c470e2bf45f9a0a79d` passed Android Build #96 (tests, Release and fixed-signature APK).

- Archive Changes now displays up to 100 recent manual/automatic audit entries, before/after values, source text and proposal/version-change records.
- Manual add/update/pin/deactivate can be reversed through a compensating mutation. Original records/logs remain, and an inverse audit plus a durable undo marker and memoryVersion advance commit atomically.
- Duplicate undo is idempotent across restart; later edits, inactive sources, and wrong timelines are rejected. The inverse is itself a recorded change and can be reversed.
- Candidate adoption cannot be partially undone via its manual-add row. Automatic ChangeSets and candidate decisions remain browse-only in this checkpoint. This is not arbitrary log replay or full M3 completion.
- Regression tests cover all four manual operations, repeat clicks/restart, conflicts, rollback, candidate decision isolation, and stale organizer results after undo. This commit requires its own CI.

### M3d — whole-batch undo for organizer output and candidate decisions

M3c commit `4bd0de02a72864bbb8d7ea9436c1e3a5639cfa9b` passed Android Build #97 (tests, Release, signed APK).

- Automatic additions and their pending proposals can be reversed together. Candidate adoption/rejection reverses its decision and generated record together; no partial manual-add undo is used.
- Inverse ChangeSets record before/after active/state values and can themselves be reversed to restore the same record/proposal IDs. Original logs remain.
- All row mutations, inverse audit, persistent duplicate marker and memoryVersion advance share a transaction. Completed organizer jobs stay completed to avoid recreating intentionally removed output.
- This conservative action requires the batch to match the current global memoryVersion and an effective source in the current route; later memory edits, source changes and route changes reject it. It does not replay arbitrary older dependent batches.
- Regression tests cover automatic undo/restore and restart, candidate adoption/rejection, later edits, cross-route/source rejection, and rollback after a partial batch write. This commit requires its own CI.
- M3/M4 still require acceptance review, including context dependency behavior, provider integration, and UI/readability polish; no stable version bump or main merge.

### Acceptance pass 1 — reading and completion contract

M3d commit `cf70ef1ad3ad2a8cd90041ac9b02df2fcd498201` passed Android Build #98.

- Story replies now reuse the ordinary chat Markdown renderer (headings/lists/code/tables and 『』 styling), instead of displaying raw Markdown as plain text.
- Pending proposals appear before the recent-change history so review actions are not buried below 100 audit rows.
- API results carry explicit output-completion metadata. Chat requires finish_reason=stop; Responses requires its completion event/status. Truncation, filtering and missing completion confirmation retain text but do not qualify a story reply as complete. Organizer output requires the same confirmation before parsing/committing. Ordinary chat still receives its text without a new retry policy.
- Added MockWebServer protocol-contract tests for Gemini-compatible Chat SSE/JSON and Responses JSON; these are synthetic gateway tests, not a real Gemini provider acceptance run.
- This checkpoint passed Android Build #100 at `7ba25bd0343962b8ae8d8e3b27ddfe257475d32e`. Remaining acceptance: on-device reading/keyboard/long-thread performance, real provider story and memory accuracy, full M1–M4 design-gap review. No main merge/version bump.


## G1a：人物认知与有向关系的数据边界（2026-09-06）

- 已接入 organizer → SQLite → archive/context 的 nature、人物主体和关系客体；人物怀疑/误解使用 CharacterBelief，禁止无主体认知、伪造用户确认、把主观认知声明为 PlotEvent。
- 同文案去重增加 kind/nature/主体/客体，避免不同人物的认知与反向关系互相吞掉。实体采用当前路线有效来源的规范名/已有别名精确匹配；多义匹配明确失败，不自动猜测合并。实体新增与资料、日志、版本处于同一事务。
- 上下文、整理器输入与档案保留主观性质和归属；固定主观看法也不提升为世界事实。角色认知不自动传播到其他人物；作者计划明确未发生。有向关系目前明确为来源轮次的观察。
- 复用 schema v2 现有字段，无数据迁移、无正式版本变动。新增实体保留供来源恢复与批次撤销恢复使用；单纯存在实体行不使任何事实可见。
- 测试覆盖：解析白名单、主体/性质约束、不同认知和方向去重、存储边界防绕过、重启、撤销/恢复、历史另写实体重映射、来源替换失效、已知别名/歧义拒绝及原子回滚。
- **边界：G1/G2 尚未关闭。** CurrentState 仍是历史观察；状态属性键、当前值选择、自动别名归并/同名消歧工作流、结构化冲突与用户解决仍待下一阶段。模型语义提取是否正确仍需真实 Gemini 验收。不会把旧 PlotEvent 自动改判成主观认知。
- 最新代码 `6b4db9a3f080515ed2fb282dcbf51e0b2d853367` 已通过 Android Build #102（https://github.com/adongshaln/Aster/actions/runs/34004045211）：单测、Release 编译、固定签名 APK 打包与上传全部成功。真实 Gemini 和真机验收仍未执行。

- CI #101 编译成功，但 SQLite 去重查询把可空引用作为字符串参数绑定，12 项存储相关测试失败。修正为 COALESCE 与非空参数，覆盖无人物引用的普通资料及单主体认知；修复后完整 CI #102 已通过。


## G1b：属性状态与固定冲突门禁（2026-09-06）

- CurrentState 使用人物引用 + 独立 state_key；数据库 schema v3 仅增加可空列，支持显式 1→2→3 / 2→3 非破坏性迁移。旧状态不猜测属性、不自动改判当前值，旧审计 JSON 保持兼容。
- 状态保留每次来源记录，按有效正文 sequence 计算当前值，不按整理完成时间覆盖。A→B→A 不会被全文去重吞掉。同源同人物同属性返回不同值时整批拒绝，包括别名解析后的校验。
- 创作上下文、整理输入、档案统一使用当前状态视图；过时记录仍保留在数据库和既有变更历史，源版本失效、批次撤销、恢复和历史另写后重新计算，无破坏性覆盖。
- 固定状态与最新值不同，或同轮有不同值，生成带双方记录/来源引用的运行时冲突。正文请求明确失败，讨论与档案仍可打开；档案展示双方和值，允许沿用已审计的修改、停用、解除固定来处理。固定资料绝不因状态筛选静默消失。
- **这不是完整 G2 冲突系统**：目前是从持久记录推导的确定性门禁，尚无独立冲突生命周期、整项采用/拒绝事务、自由文本世界观语义矛盾识别。同义属性键仍依赖整理器复用，不能宣称解决语义归并。关系仍是定向观察，并未加入可变关系属性。
- 新增状态转换、迟到任务、伤势恢复/撤销/修订、历史路线、固定冲突及真实旧库升级测试；代码 `a5fe8b9fbbc946af887808349f778ad5db75792c` 已通过 [Android Build #103](https://github.com/adongshaln/Aster/actions/runs/34004640531)：单测、Release 编译、固定签名 APK 打包上传全绿。main、应用版本、固定签名不变；尚未进行真实 Gemini 或真机验证。


## G2a：持久状态冲突、用户决定与整体撤销（2026-09-06）

- schema v4 增加独立 state_conflicts，保存双方记录 ID、来源、值/固定标志快照与指纹，状态为 pending / accepted（采用新状态）/ rejected（保留原状态）/ superseded（已不适用）。迁移为增量 3→4，测试从有数据的 v1/v2/v3 升级。
- 自动整理、手动资料变更、正文版本切换、路线恢复与批次撤销在原事务内刷新冲突；打开档案时也补齐旧库已有冲突。冲突索引的补齐不改正式资料、不单独消耗 memoryVersion；用户决定则严格增加版本。
- 处理冲突使用 UI 所见 memoryVersion 与当前路线校验，重新计算来源/内容指纹；资料停用、固定约束转移、冲突状态、ChangeSet 和版本增长为同一事务。重复决定无额外写入。双方原文不被删除。
- 档案「变更」提供双方来源、保留原状态/采用新状态；其他独立冲突不会被批量确认。采用任意一方都会停用另一条，若另一条固定则把固定约束保留在所选记录上。多项矛盾需分别处理。
- 扩展现有整体撤销：受限字段 active/pinned/state、逐条来源校验、同一最新 memoryVersion；决定与反向决定均可整批撤销/恢复，出现后续资料变更则拒绝直接撤销。日志展示决定前后及双方来源。
- 未处理冲突随旧来源失效变为 superseded；恢复相同来源/状态后重新待处理。历史另写按该路线资料快照生成独立冲突 ID；原路线的决定与日志保留，不复用其他路线的冲突按钮。
- 测试覆盖持久化/重启、双击、接受/拒绝/撤销/恢复、冲突注册与决定/撤销的注入失败回滚、旧按钮、来源替换恢复、历史路线、整理任务过期、后续编辑阻止撤销及旧库升级。等待本提交 CI。
- **边界：只处理结构化属性状态的确定性矛盾。** 自由文本世界观矛盾识别、语义同义键、跨实体知识冲突尚未实现；G2 全部范围和完整故事模式仍未完成。main、应用版本、固定签名不变。
