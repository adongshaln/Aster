# Story mode #72 regression gate

This checkpoint is limited to stabilizing the M1b workspace implementation before M2a.

Verified invariants:

- The mixed horizontal/bottom error-banner padding compiles on the current Compose version.
- Stopping before any assistant text removes the empty assistant placeholder; a non-empty paid partial response is preserved as `stopped`.
- `stopRequested` is only set when an active job exists, and a story job is registered before it can start.
- Workspace draft/scroll state receives a strictly monotonic `updatedAt`; SQLite rejects stale/equal state writes so an older asynchronous save cannot overwrite newer state.
- Manual archive removal remains a soft deactivation only. It is not described as a complete undo/revert mechanism.

M2b prerequisite: before automatic memory commits are implemented, manual archive mutations must gain an explicit change log and must atomically advance `memoryVersion`.
