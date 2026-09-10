# Whole-file Skill read validation

Implementation commit: `322331f1fe07c39a0eb02f92b387e872dd5df7c0`.

- `read_skill_file` exposes only `skill` and `path` to the model and returns the complete UTF-8 file in one tool result.
- The request-local reuse key is now canonical Skill selector + path; repeated reads reuse the prior result instead of reading the file again.
- `offset` / `next_offset` pagination is no longer part of the model-facing protocol.
- When a model context size is configured, a whole-file read is checked against that model's input-token budget before returning the file; the existing wire-context check still validates the complete subsequent request.
- Existing package, path, UTF-8, authorization and tool-loop safety boundaries remain in place.
- Migration validation ran `git diff --check` and `./gradlew --no-daemon --stacktrace testDebugUnitTest` successfully before the implementation commit was pushed.

A signed Android release build is triggered by this documentation commit to independently verify the restored standard build workflow and release signing path.
