from pathlib import Path

path = Path("app/src/main/java/com/adong/adchat/ui/screens/SettingsScreen.kt")
text = path.read_text(encoding="utf-8")
old = '''                                draft = draft.copy(chatApiMode = "chat",
                                    fileCreationEnabled = if (draft.webSearchEnabled) false else draft.fileCreationEnabled)'''
new = '''                                draft = draft.copy(
                                    chatApiMode = "chat",
                                    fileCreationEnabled = if (draft.webSearchEnabled && !searchBackendConfigured) false else draft.fileCreationEnabled
                                )'''
if text.count(old) != 1:
    raise SystemExit(f"expected one SettingsScreen anchor, found {text.count(old)}")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("Fixed Chat protocol switch for delegated search coexistence")
