package com.adong.adchat.data.story

import android.content.ContentValues
import android.content.Context
import com.adong.adchat.data.ChatCompletionResult
import java.util.UUID

data class StoryUsageTotal(
    val category: String,
    val calls: Long,
    val reported: Long,
    val input: Long,
    val output: Long,
    val unsettled: Long,
    val unsuccessful: Long,
    val recoveries: Long
)

/** Operational usage survives memory undo and route forks; it is never copied into story snapshots. */
class StoryUsageStore(context: Context) : AutoCloseable {
    private val helper = StoryDatabase(context)

    fun begin(story: String, timeline: String, category: String, profile: String, model: String, source: String?): String {
        val id = UUID.randomUUID().toString()
        helper.writableDatabase.insertOrThrow(StorySchema.USAGE,null,ContentValues().apply {
            put("id",id);put("story_id",story);put("timeline_id",timeline);put("category",category)
            put("profile_id",profile);put("model",model);put("source_id",source)
            put("state","running");put("started_at",System.currentTimeMillis())
        })
        return id
    }

    fun finish(id: String, state: String, result: ChatCompletionResult? = null): Boolean {
        require(state in setOf("completed","incomplete","failed","cancelled"))
        return helper.writableDatabase.update(StorySchema.USAGE,ContentValues().apply {
            put("state",state);put("finished_at",System.currentTimeMillis())
            result?.usage?.let { usage ->
                put("recovery_count",usage.streamRecoveryCount)
                if(usage.providerUsageReported) {
                    put("input_tokens",usage.inputTokens.coerceAtLeast(0));put("output_tokens",usage.outputTokens.coerceAtLeast(0))
                    put("cached_tokens",usage.cachedTokens.coerceAtLeast(0));put("reasoning_tokens",usage.reasoningTokens.coerceAtLeast(0))
                }
            }
        },"id=? AND state='running'",arrayOf(id)) == 1
    }

    fun recoverInterrupted(): Int = helper.writableDatabase.update(StorySchema.USAGE,ContentValues().apply {
        put("state","interrupted");put("finished_at",System.currentTimeMillis())
    },"state='running'",null)

    fun totals(story: String): List<StoryUsageTotal> = helper.readableDatabase.rawQuery("""SELECT category,COUNT(*),
        COUNT(input_tokens),COALESCE(SUM(input_tokens),0),COALESCE(SUM(output_tokens),0),
        SUM(CASE WHEN state='running' THEN 1 ELSE 0 END),
        SUM(CASE WHEN state IN ('incomplete','failed','cancelled','interrupted') THEN 1 ELSE 0 END),
        SUM(recovery_count) FROM ${StorySchema.USAGE} WHERE story_id=? GROUP BY category""",arrayOf(story)).use { c ->
        buildList { while(c.moveToNext()) add(StoryUsageTotal(c.getString(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getLong(6),c.getLong(7))) }
    }

    override fun close() = helper.close()
}

fun renderStoryUsage(totals: List<StoryUsageTotal>): String = buildString {
    if(totals.isEmpty()) append("尚无调用记录。升级前的历史用量无法补录。\n")
    val names = linkedMapOf("prose" to "正文创作","discussion" to "设定讨论","organizer" to "资料整理","summary" to "剧情摘要")
    names.forEach { (key,name) -> totals.firstOrNull { it.category == key }?.let { row ->
        append("$name · ${row.calls} 次调用\n")
        if(row.reported > 0) append("已返回：输入 ${row.input} / 输出 ${row.output} token\n")
        if(row.calls > row.reported) append("${row.calls-row.reported} 次用量未知或待返回\n")
        if(row.unsettled > 0) append("${row.unsettled} 次进行中\n")
        if(row.unsuccessful > 0) append("${row.unsuccessful} 次失败、停止或未完整结束\n")
        if(row.recoveries > 0) append("流式恢复 ${row.recoveries} 次，先前尝试可能未计入\n")
        append("\n")
    } }
    append("统计当前故事所有路线，自启用记录起累计；撤销和另写不抹除已发生调用。\n")
    append("调用次数按应用发起计，整理每个实际请求分段各计一次；接口内部重试、恢复和工具轮次不等同于此次数。仅汇总返回用量，未知不等于免费，可能低于实际账单；未估算金额。")
}
