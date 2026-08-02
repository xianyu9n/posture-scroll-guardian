package com.local.guardian

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionModelsTest {
    @Test
    fun `upright face and device is upright`() {
        assertEquals(PostureState.UPRIGHT, PostureClassifier.classify(2f, 3f, 0.25f))
    }

    @Test
    fun `sideways head relative to gravity is side lying`() {
        assertEquals(PostureState.LEFT_SIDE, PostureClassifier.classify(70f, 5f, 0.25f))
        assertEquals(PostureState.RIGHT_SIDE, PostureClassifier.classify(-75f, 0f, 0.25f))
    }

    @Test
    fun `rotated phone contributes to world head tilt`() {
        assertEquals(PostureState.LEFT_SIDE, PostureClassifier.classify(5f, 85f, 0.25f))
    }

    @Test
    fun `large face is treated as too close`() {
        assertEquals(PostureState.TOO_CLOSE, PostureClassifier.classify(0f, 0f, 0.52f))
    }

    @Test
    fun `duration and scrolling trigger doom interruption`() {
        val result = DoomScrollingPolicy.decide(
            durationMillis = 5 * 60_000L,
            scrollsLastMinute = 6,
            reopenedRecently = false,
            protectedTime = false,
            thresholdMinutes = 5,
            thresholdScrolls = 6,
        )
        assertEquals(DoomTrigger.MINDLESS_SCROLLING, result)
    }

    @Test
    fun `long intentional-looking session alone does not trigger`() {
        val result = DoomScrollingPolicy.decide(
            durationMillis = 20 * 60_000L,
            scrollsLastMinute = 1,
            reopenedRecently = false,
            protectedTime = false,
            thresholdMinutes = 5,
            thresholdScrolls = 6,
        )
        assertEquals(DoomTrigger.NONE, result)
    }

    @Test
    fun `reopening during protected time has its own trigger`() {
        val result = DoomScrollingPolicy.decide(
            durationMillis = 0L,
            scrollsLastMinute = 0,
            reopenedRecently = true,
            protectedTime = true,
            thresholdMinutes = 5,
            thresholdScrolls = 6,
        )
        assertEquals(DoomTrigger.REPETITIVE_REOPEN, result)
    }

    @Test
    fun `own accessibility overlay must not dismiss itself`() {
        assertFalse(
            OverlayPolicy.shouldDismissForPackage(
                newPackage = "com.local.guardian",
                ownPackage = "com.local.guardian",
                targetPackages = setOf("com.xingin.xhs"),
            ),
        )
    }

    @Test
    fun `target app root prevents transient window from counting as exit`() {
        assertFalse(
            AppSwitchPolicy.shouldConfirmExit(
                activePackage = "com.xingin.xhs",
                targetPackages = setOf("com.xingin.xhs"),
            ),
        )
    }

    @Test
    fun `confirmed return within a minute counts as reopening`() {
        assertTrue(AppSwitchPolicy.isRapidReopen(50_000L, 5_000L, 60_000L))
        assertFalse(AppSwitchPolicy.isRapidReopen(70_000L, 5_000L, 60_000L))
    }

    @Test
    fun `a different posture risk alerts immediately`() {
        assertTrue(
            PostureAlertPolicy.shouldAlert(
                posture = PostureState.LEFT_SIDE,
                lastAlertedPosture = PostureState.TOO_CLOSE,
                now = 30_000L,
                lastAlertAt = 29_000L,
                cooldownMillis = 120_000L,
            ),
        )
    }
}
