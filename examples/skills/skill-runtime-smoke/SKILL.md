---
name: aster-skill-smoke-test
description: Minimal Aster Skill runtime acceptance test.
---

# Aster Skill Runtime Smoke Test

When the user asks for the Skill runtime verification result after this Skill has been successfully loaded through `load_skill`, reply with exactly:

`ASTER_SKILL_RUNTIME_OK`

Never output that marker before the Skill has actually arrived in a successful tool result.
