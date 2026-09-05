from pathlib import Path

chat_path = Path("app/src/main/java/com/adong/adchat/ui/screens/ChatScreen.kt")
design_path = Path("app/src/main/java/com/adong/adchat/ui/components/AsterDesign.kt")

chat = chat_path.read_text(encoding="utf-8")
design = design_path.read_text(encoding="utf-8")


def replace_exact(text: str, old: str, new: str, expected: int = 1) -> str:
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(f"expected {expected} matches, got {actual}: {old[:100]!r}")
    return text.replace(old, new)

# Reading density: return to a calmer, denser mobile prose rhythm instead of
# stretching every paragraph like an ebook page.
chat = replace_exact(
    chat,
    'val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 29.sp)',
    'val bodyStyle = MaterialTheme.typography.bodyLarge.copy(\n        fontSize = READING_BODY_FONT_SP.sp,\n        lineHeight = READING_BODY_LINE_SP.sp,\n        fontWeight = FontWeight.Normal,\n        letterSpacing = 0.sp\n    )',
    expected=2,
)
chat = replace_exact(
    chat,
    'style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 29.sp),',
    'style = MaterialTheme.typography.bodyLarge.copy(\n            fontSize = READING_BODY_FONT_SP.sp,\n            lineHeight = READING_BODY_LINE_SP.sp,\n            fontWeight = FontWeight.Normal,\n            letterSpacing = 0.sp\n        ),',
)
chat = replace_exact(
    chat,
    'Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {',
    'Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {',
)
chat = replace_exact(
    chat,
    '''private fun StreamingProseText(content: String, error: Boolean) {
    val normalized = content.replace("\\r\\n", "\\n")
    val parts = remember(normalized) { normalized.split("\\n\\n") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {''',
    '''private fun StreamingProseText(content: String, error: Boolean) {
    val normalized = content.replace("\\r\\n", "\\n")
    val parts = remember(normalized) { normalized.split("\\n\\n") }
    Column(verticalArrangement = Arrangement.spacedBy(READING_BLOCK_GAP_DP.dp)) {''',
)
chat = replace_exact(
    chat,
    '''private fun StructuredMessageText(content: String, streaming: Boolean, error: Boolean) {
    val parts = remember(content) { content.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {''',
    '''private fun StructuredMessageText(content: String, streaming: Boolean, error: Boolean) {
    val parts = remember(content) { content.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {''',
)

# ATX Markdown headings are valid through level 6. The old reading parser only
# recognized 1..3, which exposed literal #### in prose.
chat = replace_exact(
    chat,
    'line.matches(Regex("""^#{1,3}\\s+.*""")) -> {',
    'line.matches(Regex("""^#{1,6}\\s+.*""")) -> {',
)

# Keep section hierarchy visible without making every small heading dominate a
# phone screen. H4-H6 become compact section labels rather than raw Markdown.
chat = replace_exact(
    chat,
    '''style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(lineHeight = 31.sp)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp)
                        else -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                    },''',
    '''style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        2 -> MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.5.sp,
                            lineHeight = 25.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        3 -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                        else -> bodyStyle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                    },''',
)
chat = replace_exact(
    chat,
    'modifier = Modifier.padding(top = if (block.level == 1) 10.dp else 5.dp)',
    'modifier = Modifier.padding(top = if (block.level == 1) 8.dp else 3.dp)',
)
chat = replace_exact(
    chat,
    'Modifier.fillMaxWidth().padding(vertical = 8.dp),',
    'Modifier.fillMaxWidth().padding(vertical = 5.dp),',
)

# The animated thinking mark must be only the star glyph. The launcher
# foreground includes the square brand tile, which looks like a rotating card.
old_artwork = '''/** Full-colour transparent Aster artwork for branded motion and larger identity moments. */
@Composable
fun AsterArtwork(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = modifier,
        tint = Color.Unspecified
    )
}'''
new_artwork = '''/** Transparent Aster star used for branded motion without the launcher tile. */
@Composable
fun AsterArtwork(modifier: Modifier = Modifier) {
    AsterMark(modifier = modifier, tint = Accent)
}'''
design = replace_exact(design, old_artwork, new_artwork)

chat_path.write_text(chat, encoding="utf-8")
design_path.write_text(design, encoding="utf-8")
print("Reading refinements applied")
