# Story discussion workspace boundary validation

Date: 2026-09-11

The story Discussion workspace has a mandatory system-level no-prose guard. `StoryContextComposer` injects it into every Discussion request after the caller-provided base instruction, while the Prose workspace remains unchanged.

Regression coverage verifies that an instruction to continue prose cannot remove the Discussion guard, ambiguous continuation language remains discussion material, Skills cannot override the boundary, and Prose requests do not receive the Discussion guard.

The migration unit-test run passed before the source commit. This commit triggers the standard Android release workflow for signed APK verification.
