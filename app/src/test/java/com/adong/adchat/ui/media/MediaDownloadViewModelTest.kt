package com.adong.adchat.ui.media

import androidx.lifecycle.ViewModelStore
import com.adong.adchat.media.ResolvedMedia
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MediaDownloadViewModelTest {
    private lateinit var vm: MediaDownloadViewModel
    private val owner = ViewModelStore()

    @Before fun create() {
        vm = MediaDownloadViewModel(RuntimeEnvironment.getApplication())
        owner.put("media", vm)
    }

    @After fun clear() = owner.clear()

    @Test fun invalidNewInputInvalidatesThePreviousResolver() {
        val oldToken = start("1234567890123456789")
        vm.updateInput("这不是链接")
        vm.resolve()
        val rejected = vm.state.value

        vm.onResolved(result, oldToken)
        vm.onResolverProgress("旧页面进度", oldToken)
        vm.onResolveFailed("旧页面失败", oldToken)

        assertEquals(MediaWorkspacePhase.Error, vm.state.value.phase)
        assertEquals(rejected, vm.state.value)
    }

    @Test fun cancelledWebViewCannotReopenTheResultOrVerification() {
        val token = start("1234567890123456789")
        vm.cancelResolve()
        val cancelled = vm.state.value

        vm.onVerificationRequired("完成验证", token)
        vm.onResolved(result, token)

        assertEquals(cancelled, vm.state.value)
        assertNull(vm.state.value.resolvedMedia)
    }

    @Test fun callbacksFromPreviousPageDoNotChangeTheNewRequest() {
        val first = start("1234567890123456789")
        val second = start("2234567890123456789")
        vm.onResolved(result, first)
        assertEquals(MediaWorkspacePhase.Resolving, vm.state.value.phase)
        assertEquals(second, vm.state.value.resolveRequest?.token)

        vm.onVerificationRequired("完成验证", second)
        assertEquals(MediaWorkspacePhase.Verification, vm.state.value.phase)
        vm.onResolved(result, second)
        assertEquals(result, vm.state.value.resolvedMedia)
        assertEquals(MediaWorkspacePhase.Ready, vm.state.value.phase)
    }

    @Test fun lateCallbacksCannotReplaceACompletedResult() {
        val token = start("1234567890123456789")
        vm.onResolved(result, token)
        val completed = vm.state.value

        vm.onResolveFailed("导航失败", token)
        vm.onResolved(result.copy(title = "重复结果"), token)

        assertEquals(completed, vm.state.value)
    }

    private fun start(id: String): Long {
        vm.updateInput("https://www.douyin.com/video/$id")
        vm.resolve()
        return requireNotNull(vm.state.value.resolveRequest).token
    }

    private val result = ResolvedMedia(
        videoId = "1234567890123456789",
        title = "视频",
        mediaUrl = "https://media.example/video.mp4",
        sourceUrl = "https://www.douyin.com/video/1234567890123456789",
        referer = "https://www.douyin.com/",
        userAgent = "test"
    )
}
