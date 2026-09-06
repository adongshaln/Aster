package com.adong.adchat.data.story

/** A conflict retains both durable records and their source revision IDs; no automatic mutation. */
data class StoryStateConflict(val earlier: StoryMemoryRecord, val latest: StoryMemoryRecord) {
    val description: String get() = "${latest.subjectEntityNames.firstOrNull() ?: "人物"} · ${latest.stateKey}：" +
        "「${earlier.content}」（轮次 ${earlier.effectiveSequence}）与「${latest.content}」（轮次 ${latest.effectiveSequence}）不一致"
}

data class StoryStateView(val records: List<StoryMemoryRecord>, val conflicts: List<StoryStateConflict>)

class StoryStateConflictException(val conflicts: List<StoryStateConflict>) : IllegalStateException(
    "当前状态存在冲突，正文尚未发送。请到故事档案修改或停用错误记录；若固定状态已允许随剧情变化，请解除固定。\n" +
        conflicts.take(3).joinToString("\n") { it.description }
)

/** Input must already exclude obsolete source revisions (StoryArchiveStore enforces this). */
object StoryStateProjection {
    fun project(records: List<StoryMemoryRecord>): StoryStateView {
        val active = records.filter { it.active }
        val structured = active.filter {
            it.kind == StoryMemoryKind.CurrentState && it.stateKey != null && it.subjectEntityId != null &&
                it.nature in setOf(StoryMemoryNature.ProseOccurred, StoryMemoryNature.UserConfirmed)
        }
        val selected = mutableSetOf<String>()
        val conflicts = mutableListOf<StoryStateConflict>()
        structured.groupBy { listOf(it.storyId, it.timelineId, it.subjectEntityId, it.stateKey) }.values.forEach { group ->
            // Source order, never organizer completion time. Old jobs can finish after newer jobs.
            val latest = group.sortedWith(compareByDescending<StoryMemoryRecord> { it.effectiveSequence }
                .thenByDescending { it.updatedAt }.thenBy { it.id }).first()
            selected += latest.id
            group.filter { it.pinned || it.effectiveSequence == latest.effectiveSequence }.forEach { record ->
                if (record.pinned) selected += record.id // Fixed material is never silently omitted.
                if (record.id != latest.id && record.content.trim() != latest.content.trim()) {
                    selected += record.id
                    conflicts += StoryStateConflict(record, latest)
                }
            }
        }
        val structuredIds = structured.map { it.id }.toSet()
        return StoryStateView(active.filter { it.id !in structuredIds || it.id in selected }, conflicts)
    }
}
