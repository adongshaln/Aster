package com.adong.adchat.data.story

import android.content.Context
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class StoryRewritesTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var archive: StoryArchiveStore
    private lateinit var story: Story
    private lateinit var source: StoryMessageWithRevision
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo=StoryRepository(context);archive=StoryArchiveStore(context);story=repo.createStory("重写","p","m")
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"user","描写抵达港口")
        source=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","她乘船抵达港口。")
    }
    @After fun close() { archive.close();repo.close() }
    private fun begin()=repo.beginRewrite(source.message.id,source.revision.id,repo.getStory(story.id)!!.memoryVersion,"改为步行抵达","测试服务","m")
    private fun ready(): StoryRewriteCandidate = begin().also { repo.updateRewrite(it.id,"她步行抵达港口。","ready") }

    @Test fun previewIsDurableAndDiscussionAfterProseDoesNotBlockAtomicAdoption() {
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"user","讨论步行是否合适")
        val version=repo.getStory(story.id)!!.memoryVersion;val candidate=ready()
        assertEquals(source.revision.id,repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose).last().revision.id)
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion)
        repo.close();repo=StoryRepository(context)
        assertEquals("ready",repo.latestRewrite(source.message.id)!!.state)
        val adopted=repo.adoptRewrite(candidate.id)
        assertEquals("她步行抵达港口。",adopted.revision.content)
        assertEquals(version+1,repo.getStory(story.id)!!.memoryVersion)
        assertEquals(2,repo.listRevisions(source.message.id).size)
        assertEquals("adopted",repo.latestRewrite(source.message.id)!!.state)
        assertThrows(IllegalStateException::class.java) { repo.adoptRewrite(candidate.id) }
        assertEquals(2,repo.listRevisions(source.message.id).size)
        assertTrue(repo.restoreMessageRevision(source.message.id,source.revision.id,adopted.revision.id))
    }

    @Test fun changedMemoryAndNewProseRejectStaleCandidates() {
        val candidate=ready()
        archive.addConfirmedRecord(story.id,story.currentTimelineId,StoryMemoryKind.WorldFact,"新增正式设定")
        assertThrows(IllegalStateException::class.java) { repo.adoptRewrite(candidate.id) }
        val next=ready()
        repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"user","下一段")
        assertThrows(IllegalArgumentException::class.java) { repo.adoptRewrite(next.id) }
        assertThrows(IllegalArgumentException::class.java) { begin() }
        assertEquals(1,repo.listRevisions(source.message.id).size)
    }

    @Test fun stoppedIncompleteFailedAndRecoveredCandidatesCannotBecomeFacts() {
        for(state in listOf("stopped","incomplete","failed")) {
            val candidate=begin();assertTrue(repo.updateRewrite(candidate.id,"部分文字",state))
            assertFalse(repo.updateRewrite(candidate.id,"迟到的完整结果","ready"))
            assertThrows(IllegalStateException::class.java) { repo.adoptRewrite(candidate.id) }
        }
        val unfinished=begin();repo.updateRewrite(unfinished.id,"恢复前片段","generating")
        repo.close();repo=StoryRepository(context)
        assertEquals(1,repo.recoverRewrites());assertEquals(0,repo.recoverRewrites())
        assertEquals("恢复前片段",repo.latestRewrite(source.message.id)!!.content)
        assertThrows(IllegalStateException::class.java) { repo.adoptRewrite(unfinished.id) }
    }

    @Test fun adoptionFailureRollsBackRevisionVersionAndCandidateState() {
        val candidate=ready();val version=repo.getStory(story.id)!!.memoryVersion
        val helper=StoryDatabase(context)
        helper.writableDatabase.execSQL("CREATE TRIGGER fail_adopt BEFORE UPDATE ON story_rewrites WHEN NEW.state='adopted' BEGIN SELECT RAISE(ABORT,'failure'); END")
        assertThrows(Exception::class.java) { repo.adoptRewrite(candidate.id) }
        assertEquals(version,repo.getStory(story.id)!!.memoryVersion)
        assertEquals(1,repo.listRevisions(source.message.id).size)
        assertEquals("ready",repo.latestRewrite(source.message.id)!!.state)
        helper.close()
    }

    @Test fun contextDoesNotTreatReplacedFactsOrDiscussionAsConfirmedAndKeepsPinnedRules() {
        val ordinary=StoryMemoryRecord(storyId=story.id,timelineId=story.currentTimelineId,kind=StoryMemoryKind.PlotEvent,
            content="旧正文派生事实",nature=StoryMemoryNature.ProseOccurred,sourceRevisionId=source.revision.id)
        val pinned=ordinary.copy(id="pinned",content="必须保留固定规则",pinned=true)
        val snapshot=StoryContextMemorySnapshot(listOf(ordinary,pinned),emptyList(),emptySet())
        val discussion=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Discussion,"assistant","讨论秘密，不得泄漏")
        val result=StoryRewriteContext.compose(source,"改成步行",snapshot,
            repo.loadMessages(story.id,story.currentTimelineId,StoryWorkspace.Prose)+discussion)
        val all=result.systemPrompt+result.history.joinToString { it.content }
        assertTrue(all.contains("必须保留固定规则"));assertTrue(all.contains("改成步行"))
        assertTrue(all.contains(source.revision.content));assertFalse(all.contains("旧正文派生事实"));assertFalse(all.contains("讨论秘密"))
        assertThrows(StoryContextOverflowException::class.java) {
            StoryRewriteContext.compose(source.copy(revision=source.revision.copy(content="字".repeat(48000))),"改写",snapshot,emptyList())
        }
}
}
