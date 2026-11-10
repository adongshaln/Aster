package com.adong.adchat.data.rp.helper

import com.adong.adchat.data.rp.InMemoryRpVariableStore
import com.adong.adchat.data.rp.RpHelperActions
import com.adong.adchat.data.rp.RpHelperMessage
import com.adong.adchat.data.rp.RpHelperMessageUpdate
import com.adong.adchat.data.rp.RpSlashEnv
import com.adong.adchat.data.rp.RpVarScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RpSlashCommandsTest {
    private class FakeActions : RpHelperActions {
        val log = mutableListOf<String>()
        var msgs = (0 until 5).map { RpHelperMessage(it, it + 100L, "user", "u", "m$it", listOf("m$it"), 0, false) }
        override fun messages() = msgs
        override fun setMessages(updates: List<RpHelperMessageUpdate>) { updates.forEach { log += "set ${it.index} hidden=${it.hidden}" } }
        override fun createMessages(items: List<Pair<String, String>>, insertBefore: Int?) {}
        override fun deleteMessages(indices: List<Int>) { log += "del $indices" }
        override fun sendUser(text: String) { log += "send $text" }
        override fun sendAs(role: String, name: String, text: String) { log += "as $role/$name $text" }
        override fun trigger() { log += "trigger" }
        override fun setInput(text: String) { log += "input $text" }
        override fun toast(text: String) { log += "toast $text" }
        override fun context() = JSONObject()
        override suspend fun generate(prompt: String, options: JSONObject): String { log += "gen $prompt ${options.optBoolean("lock")}"; return "生成结果" }
    }

    private val actions = FakeActions()
    private val vars = InMemoryRpVariableStore()
    private val env = RpSlashEnv(actions, vars) { it.replace("{{user}}", "小明") }
    private fun run(s: String) = runBlocking { RpSlashCommands.run(s, env) }

    @Test
    fun sendTriggerAndMacros() {
        run("/send 我选择 A，{{user}}|/trigger")
        assertEquals(listOf("send 我选择 A，小明", "trigger"), actions.log)
    }

    @Test
    fun quotedPipeIsNotSplit() {
        run("/sendas name=\"艾 莉\" \"a|b\"|/sys 旁白|/comment 注|/setinput 草稿|/echo 提示")
        assertEquals(listOf("as assistant/艾 莉 a|b", "as system/ 旁白", "as comment/ 注", "input 草稿", "toast 提示"), actions.log)
    }

    @Test
    fun variablesFollowStSemantics() {
        assertEquals("9", run("/setvar key=hp 5|/addvar key=hp 3|/incvar hp|/getvar hp"))
        assertEquals(9, vars.table(RpVarScope.Chat).get("hp"))
        assertEquals("ab", run("/setvar key=s a|/addvar key=s b|/getvar s"))
        run("/setvar key=list [\"x\"]|/addvar key=list y")
        assertEquals("[\"x\",\"y\"]", vars.table(RpVarScope.Chat).get("list").toString())
        assertEquals("y", run("/getvar key=list index=1"))
        run("/setvar key=obj index=k 3")
        assertEquals("3", run("/getvar key=obj index=k"))
        assertEquals("-1", run("/decglobalvar g|/getglobalvar g"))
        run("/setglobalvar key=g2 x|/flushglobalvar g2")
        assertEquals(false, vars.table(RpVarScope.Global).has("g2"))
        assertEquals("", run("/flushvar hp|/getvar hp"))
    }

    @Test
    fun pipeFlowsIntoNextCommand() {
        assertEquals("3", run("/pass abc|/len"))
        assertEquals("abc!", run("/pass abc|/pass {{pipe}}!"))
        assertEquals("早", run("/return 早|/pass 晚"))
        assertEquals("你好。", run("/trimend 你好。不完整"))
    }

    @Test
    fun genAndButtonsAndInput() {
        assertEquals("生成结果", run("/gen lock=on 写一首诗"))
        assertEquals("gen 写一首诗 true", actions.log.last())
        assertEquals("选项一", run("/buttons labels=[\"选项一\",\"选项二\"] 请选择"))
        assertEquals("", run("/input 名字?"))
    }

    @Test
    fun hideCutDel() {
        run("/hide 1-2|/unhide 4|/del 2")
        assertEquals(listOf("set 1 hidden=true", "set 2 hidden=true", "set 4 hidden=false", "del [3, 4]"), actions.log)
        assertEquals("m0", run("/cut 0"))
    }

    @Test
    fun ifAndTimesRunClosures() {
        run("/setvar key=hp 10")
        run("/if left=hp right=5 rule=gt {: /echo 高 :} else={: /echo 低 :}")
        run("/if left=hp right=50 rule=gte {: /echo 高 :} else={: /echo 低 :}")
        run("/if left=名字 right=名 rule=in \"/echo 包含\"")
        assertEquals(listOf("toast 高", "toast 低", "toast 包含"), actions.log)
        run("/times 3 {: /addvar key=n 1 | /echo {{timesIndex}} :}")
        assertEquals(3, vars.table(RpVarScope.Chat).get("n"))
        assertEquals(listOf("toast 0", "toast 1", "toast 2"), actions.log.takeLast(3))
        run("/if left=1 right=1 {: /if left=a right=b rule=neq {: /echo 嵌套 :} :}")
        assertEquals("toast 嵌套", actions.log.last())
    }

    @Test
    fun unknownCommandsPassPipe() {
        assertEquals("x", run("/pass x|/whatever 1|//comment"))
    }
}
