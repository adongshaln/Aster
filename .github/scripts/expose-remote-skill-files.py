from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    file.write_text(text.replace(old, new, 1), encoding="utf-8")


replace_once(
    "app/src/main/java/com/adong/adchat/data/ToolProtocol.kt",
    '.put("files", JSONArray(skill.files.keys.sorted()))',
    '.put("files", JSONArray(skill.filePaths.sorted()))',
    "load_skill remote file manifest",
)

runtime_test = Path("app/src/test/java/com/adong/adchat/data/SkillRuntimeTest.kt")
text = runtime_test.read_text(encoding="utf-8")
old = '''                sha256 = "0123456789abcdef",
                content = "---\\nname: presentation-design\\n---\\n# Exact skill body\\nDo the real work."
            )'''
new = '''                sha256 = "0123456789abcdef",
                content = "---\\nname: presentation-design\\n---\\n# Exact skill body\\nDo the real work.",
                remoteFiles = setOf("references/themes.md")
            )'''
if text.count(old) != 1:
    raise SystemExit("test remoteFiles fixture: expected exactly one match")
text = text.replace(old, new, 1)
old_assert = '''        assertEquals("---\\nname: presentation-design\\n---\\n# Exact skill body\\nDo the real work.", output.getString("content"))'''
new_assert = '''        assertEquals("---\\nname: presentation-design\\n---\\n# Exact skill body\\nDo the real work.", output.getString("content"))
        assertEquals(listOf("references/themes.md"), (0 until output.getJSONArray("files").length()).map { output.getJSONArray("files").getString(it) })'''
if text.count(old_assert) != 1:
    raise SystemExit("test files assertion: expected exactly one match")
runtime_test.write_text(text.replace(old_assert, new_assert, 1), encoding="utf-8")
