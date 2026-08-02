package com.local.guardian

import kotlin.math.abs

enum class PostureState {
    UPRIGHT,
    LEFT_SIDE,
    RIGHT_SIDE,
    TOO_CLOSE,
    UNKNOWN;

    val isRisky: Boolean
        get() = this == LEFT_SIDE || this == RIGHT_SIDE || this == TOO_CLOSE
}

object PostureClassifier {
    fun classify(faceRollDegrees: Float, deviceRollDegrees: Float, faceWidthRatio: Float): PostureState {
        if (!faceRollDegrees.isFinite() || !deviceRollDegrees.isFinite() || !faceWidthRatio.isFinite()) {
            return PostureState.UNKNOWN
        }
        if (faceWidthRatio >= 0.48f) return PostureState.TOO_CLOSE

        val worldTilt = normalize(faceRollDegrees + deviceRollDegrees)
        return when {
            worldTilt >= 55f && worldTilt <= 125f -> PostureState.LEFT_SIDE
            worldTilt <= -55f && worldTilt >= -125f -> PostureState.RIGHT_SIDE
            abs(worldTilt) < 40f || abs(worldTilt) > 140f -> PostureState.UPRIGHT
            else -> PostureState.UNKNOWN
        }
    }

    private fun normalize(value: Float): Float {
        var result = value
        while (result > 180f) result -= 360f
        while (result < -180f) result += 360f
        return result
    }
}

enum class DoomTrigger { NONE, MINDLESS_SCROLLING, REPETITIVE_REOPEN }

object DoomScrollingPolicy {
    fun decide(
        durationMillis: Long,
        scrollsLastMinute: Int,
        reopenedRecently: Boolean,
        protectedTime: Boolean,
        thresholdMinutes: Int,
        thresholdScrolls: Int,
    ): DoomTrigger = when {
        reopenedRecently && protectedTime -> DoomTrigger.REPETITIVE_REOPEN
        durationMillis >= thresholdMinutes * 60_000L && scrollsLastMinute >= thresholdScrolls ->
            DoomTrigger.MINDLESS_SCROLLING
        else -> DoomTrigger.NONE
    }
}

object OverlayPolicy {
    fun shouldDismissForPackage(
        newPackage: String,
        ownPackage: String,
        targetPackages: Set<String>,
    ): Boolean = newPackage != ownPackage && newPackage !in targetPackages
}

object AppSwitchPolicy {
    fun shouldConfirmExit(activePackage: String?, targetPackages: Set<String>): Boolean =
        activePackage !in targetPackages

    fun isRapidReopen(now: Long, lastConfirmedExitAt: Long, reopenWindowMillis: Long): Boolean =
        now - lastConfirmedExitAt in 1..reopenWindowMillis
}

object PostureAlertPolicy {
    fun shouldAlert(
        posture: PostureState,
        lastAlertedPosture: PostureState,
        now: Long,
        lastAlertAt: Long,
        cooldownMillis: Long,
    ): Boolean = posture != lastAlertedPosture || now - lastAlertAt >= cooldownMillis
}
