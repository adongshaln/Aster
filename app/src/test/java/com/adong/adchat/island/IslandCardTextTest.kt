package com.adong.adchat.island

import com.adong.adchat.media.MediaPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandCardTextTest {
    @Test fun titleNamesThePlatform() {
        assertEquals("抖音视频", IslandCardText.title(MediaPlatform.Douyin))
        assertEquals("B 站视频", IslandCardText.title(MediaPlatform.Bilibili))
        assertEquals("X 媒体", IslandCardText.title(MediaPlatform.Twitter))
        assertEquals("媒体链接", IslandCardText.title(MediaPlatform.Direct))
    }

    @Test fun summaryDropsSchemeWwwAndTrailingSlash() {
        assertEquals("v.douyin.com/one", IslandCardText.summary("https://v.douyin.com/one/"))
        assertEquals(
            "bilibili.com/video/BV1xx411c7mD?p=2",
            IslandCardText.summary("https://www.bilibili.com/video/BV1xx411c7mD?p=2")
        )
        // The scheme is stripped case-insensitively; "www." is not present here.
        assertEquals("X.com/a/status/1", IslandCardText.summary("HTTP://X.com/a/status/1"))
        assertEquals("b23.tv/abc", IslandCardText.summary("  https://b23.tv/abc  "))
    }

    @Test fun summaryFallsBackToTheRawLinkWhenNothingIsLeftToStrip() {
        // A scheme with no host would otherwise collapse to an empty caption.
        assertEquals("https://", IslandCardText.summary("https://"))
    }

    @Test fun summaryEllipsizesPastTheCaptionBudget() {
        val summary = IslandCardText.summary("https://x.com/" + "a".repeat(300))
        assertEquals(IslandCardText.MAX_SUMMARY_CHARS, summary.length)
        assertTrue("over-long captions must end in an ellipsis; summary=$summary", summary.endsWith("…"))
    }

    @Test fun contentAnalyzerDetectsPickupCodesSchedulesForeignTextAndLongArticles() {
        val pickup = StarIslandContent("【菜鸟驿站】您的包裹已到站，请凭取件码 3-2-1048 到 2 号货架取件。")
        assertEquals(StarIslandTextScene.PickupOrCode, pickup.textInsight.scene)
        assertEquals("3-2-1048", pickup.textInsight.extractedCode)
        assertEquals("取件 / 验证码", IslandCardText.title(pickup))
        assertTrue(StarIslandAction.CopyExtracted in pickup.textInsight.primaryActions)

        val otp = StarIslandContent("Your GitHub verification code is 849201. Do not share it.")
        assertEquals(StarIslandTextScene.PickupOrCode, otp.textInsight.scene)
        assertEquals("849201", otp.textInsight.extractedCode)

        val schedule = StarIslandContent("明天下午 3:30 在 402 会议室召开产品评审会，请准时参加。")
        assertEquals(StarIslandTextScene.Schedule, schedule.textInsight.scene)
        assertEquals("日程与时间", IslandCardText.title(schedule))
        assertEquals(StarIslandAction.AddCalendar, schedule.textInsight.primaryActions.first())

        val foreign = StarIslandContent("Grounding with Google Search connects the Gemini model to real-time web content and works with all available languages.")
        assertEquals(StarIslandTextScene.ForeignText, foreign.textInsight.scene)
        assertEquals(StarIslandAction.Translate, foreign.textInsight.primaryActions.first())

        val article = StarIslandContent("这是一篇长文章。".repeat(40))
        assertEquals(StarIslandTextScene.LongArticle, article.textInsight.scene)
        assertEquals(StarIslandAction.Summarize, article.textInsight.primaryActions.first())
    }
}
