package com.adong.adchat.data.story

import android.content.Context
import com.adong.adchat.data.ChatCompletionResult
import com.adong.adchat.data.TokenUsage
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
class StoryUsageStoreTest {
    private lateinit var context: Context
    private lateinit var repo: StoryRepository
    private lateinit var usage: StoryUsageStore
    private lateinit var story: Story
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication();context.deleteDatabase(StoryDatabase.DATABASE_NAME)
        repo=StoryRepository(context);usage=StoryUsageStore(context);story=repo.createStory("用量","p","m")
    }
    @After fun close() { usage.close();repo.close() }
    private fun begin(category: String="prose")=usage.begin(story.id,story.currentTimelineId,category,"p","m",null)
    private fun result(input: Int=0,output: Int=0,reported: Boolean=true)=ChatCompletionResult("正文",TokenUsage(inputTokens=input,outputTokens=output,providerUsageReported=reported))

    @Test fun unknownIsDifferentFromExplicitZeroAndFinishIsIdempotent() {
        val zero=begin();assertTrue(usage.finish(zero,"completed",result()))
        assertFalse(usage.finish(zero,"completed",result(100,200)))
        usage.finish(begin(),"completed",result(reported=false))
        usage.finish(begin(),"failed")
        val row=usage.totals(story.id).single()
        assertEquals(3L,row.calls);assertEquals(1L,row.reported)
        assertEquals(0L,row.input);assertEquals(1L,row.unsuccessful)
        assertTrue(renderStoryUsage(listOf(row)).contains("2 次用量未知"))
    }

    @Test fun retriesAreSeparateCallsAndRestartPreservesInterruptedUnknown() {
        usage.finish(begin("organizer"),"failed")
        usage.finish(begin("organizer"),"completed",result(123,45))
        begin("summary")
        usage.close();usage=StoryUsageStore(context)
        assertEquals(1,usage.recoverInterrupted());assertEquals(0,usage.recoverInterrupted())
        val rows=usage.totals(story.id).associateBy { it.category }
        assertEquals(2L,rows.getValue("organizer").calls)
        assertEquals(123L,rows.getValue("organizer").input)
        assertEquals(0L,rows.getValue("summary").reported)
        assertEquals(1L,rows.getValue("summary").unsuccessful)
    }

    @Test fun forkDoesNotDuplicateUsageOrEraseOriginalRouteAndStoriesStayIsolated() {
        usage.finish(begin(),"completed",result(10,20))
        val message=repo.appendMessage(story.id,story.currentTimelineId,StoryWorkspace.Prose,"assistant","原文")
        val branch=repo.forkProseRevision(message.message.id,message.revision.id,"另写")
        assertEquals(1L,usage.totals(story.id).single().calls)
        usage.finish(usage.begin(story.id,branch,"prose","p","new-model",null),"completed",result(30,40))
        assertEquals(40L,usage.totals(story.id).single().input)
        val other=repo.createStory("其他","p","m")
        assertTrue(usage.totals(other.id).isEmpty())
    }

    @Test fun incompleteOutputStillCountsReturnedUsageAndTotalsUseLong() {
        repeat(2) { usage.finish(begin(),"incomplete",result(Int.MAX_VALUE,20).copy(outputComplete=false)) }
        val row=usage.totals(story.id).single()
        assertEquals(4294967294L,row.input);assertEquals(40L,row.output);assertEquals(2L,row.unsuccessful)
        val id=begin("discussion");usage.finish(id,"cancelled")
        assertEquals(0L,usage.totals(story.id).first { it.category=="discussion" }.reported)
    }
}
