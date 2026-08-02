package com.local.guardian

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.ArrayDeque

class GuardianAccessibilityService : AccessibilityService() {
    private enum class InterventionKind { DOOM_SCROLLING, TOO_CLOSE, SIDE_LYING }

    private val scrollTimes = ArrayDeque<Long>()
    private var currentPackage: String? = null
    private var sessionStartedAt = 0L
    private var lastExitAt = 0L
    private var reopenedRecently = false
    private var lastDoomInterventionAt = 0L
    private var overlay: View? = null
    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var pendingExitAt = 0L
    private var pendingExitCheck: Runnable? = null

    private val postureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val posture = runCatching {
                PostureState.valueOf(intent.getStringExtra(EXTRA_POSTURE) ?: return)
            }.getOrDefault(PostureState.UNKNOWN)
            if (posture.isRisky && currentPackage in GuardianPrefs.targetPackages(this@GuardianAccessibilityService)) {
                val isTooClose = posture == PostureState.TOO_CLOSE
                showIntervention(
                    kind = if (isTooClose) InterventionKind.TOO_CLOSE else InterventionKind.SIDE_LYING,
                    title = if (isTooClose) "距离太近了！" else GuardianPrefs.postureTitle(this@GuardianAccessibilityService),
                    buttonText = if (isTooClose) {
                        GuardianPrefs.distanceButton(this@GuardianAccessibilityService)
                    } else {
                        GuardianPrefs.postureButton(this@GuardianAccessibilityService)
                    },
                    urgent = true,
                )
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val filter = IntentFilter(ACTION_POSTURE_ALERT)
        ContextCompat.registerReceiver(
            this,
            postureReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val targets = GuardianPrefs.targetPackages(this)
        val now = System.currentTimeMillis()

        // An accessibility overlay can emit a window event for this app itself.
        // It is not a real app switch, so keep both the intervention and target session intact.
        if (pkg == packageName && overlay != null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg in targets) {
                cancelPendingExit()
                if (currentPackage !in targets) {
                    reopenedRecently = AppSwitchPolicy.isRapidReopen(now, lastExitAt, REOPEN_WINDOW_MS)
                    scrollTimes.clear()
                    sessionStartedAt = now
                }
                currentPackage = pkg
            } else if (currentPackage in targets) {
                scheduleExitConfirmation(now)
            } else if (pkg != packageName) {
                currentPackage = pkg
            }
            if (pkg in targets && sessionStartedAt == 0L) {
                sessionStartedAt = now
            }
        }
        if (pkg !in targets) return
        if (currentPackage == null) currentPackage = pkg
        if (sessionStartedAt == 0L) sessionStartedAt = now

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scrollTimes.addLast(now)
            while (scrollTimes.isNotEmpty() && now - scrollTimes.first() > 60_000L) {
                scrollTimes.removeFirst()
            }
        }

        if (overlay != null || now - lastDoomInterventionAt < GuardianPrefs.doomRepeatSeconds(this) * 1_000L) return
        val trigger = DoomScrollingPolicy.decide(
            durationMillis = now - sessionStartedAt,
            scrollsLastMinute = scrollTimes.size,
            reopenedRecently = reopenedRecently,
            protectedTime = GuardianPrefs.isProtectedTime(this),
            thresholdMinutes = GuardianPrefs.riskMinutes(this),
            thresholdScrolls = GuardianPrefs.riskScrolls(this),
        )
        if (trigger != DoomTrigger.NONE) {
            lastDoomInterventionAt = now
            reopenedRecently = false
            showIntervention(
                kind = InterventionKind.DOOM_SCROLLING,
                title = if (trigger == DoomTrigger.REPETITIVE_REOPEN) {
                    "你重复退出又打开了哦~"
                } else {
                    "你在无意义地滑手机吗？"
                },
                buttonText = GuardianPrefs.doomButton(this),
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        cancelPendingExit()
        dismissOverlay()
        runCatching { unregisterReceiver(postureReceiver) }
        super.onDestroy()
    }

    private fun showIntervention(
        kind: InterventionKind,
        title: String,
        buttonText: String,
        urgent: Boolean = false,
    ) {
        if (overlay != null && !urgent) return
        if (urgent) dismissOverlay()
        vibrate()

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val backgroundColor = when (kind) {
            InterventionKind.DOOM_SCROLLING -> Color.rgb(25, 32, 28)
            InterventionKind.TOO_CLOSE -> Color.rgb(83, 35, 24)
            InterventionKind.SIDE_LYING -> Color.rgb(35, 38, 86)
        }
        val category = when (kind) {
            InterventionKind.DOOM_SCROLLING -> "使用提醒"
            InterventionKind.TOO_CLOSE -> "距离警报"
            InterventionKind.SIDE_LYING -> "姿势警报"
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(48), dp(28), dp(48))
            setBackgroundColor(Color.argb(250, Color.red(backgroundColor), Color.green(backgroundColor), Color.blue(backgroundColor)))
            addView(TextView(this@GuardianAccessibilityService).apply {
                text = category
                textSize = 16f
                setTextColor(Color.rgb(255, 205, 120))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            })
            addView(TextView(this@GuardianAccessibilityService).apply {
                text = title
                textSize = 28f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(30))
            })
            addView(Button(this@GuardianAccessibilityService).apply {
                text = buttonText
                isAllCaps = false
                setPadding(0, dp(4), 0, dp(4))
                setOnClickListener { dismissOverlay() }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        overlay = panel
        runCatching { windowManager.addView(panel, params) }
            .onFailure { overlay = null }
    }

    private fun dismissOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun scheduleExitConfirmation(observedAt: Long) {
        if (pendingExitCheck != null) return
        pendingExitAt = observedAt
        val check = Runnable {
            val targets = GuardianPrefs.targetPackages(this)
            val activePackage = rootInActiveWindow?.packageName?.toString()
            if (AppSwitchPolicy.shouldConfirmExit(activePackage, targets)) {
                lastExitAt = pendingExitAt
                currentPackage = activePackage
                scrollTimes.clear()
                dismissOverlay()
            }
            pendingExitAt = 0L
            pendingExitCheck = null
        }
        pendingExitCheck = check
        handler.postDelayed(check, EXIT_CONFIRM_MS)
    }

    private fun cancelPendingExit() {
        pendingExitCheck?.let(handler::removeCallbacks)
        pendingExitCheck = null
        pendingExitAt = 0L
    }

    private fun vibrate() {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 160, 100, 260), -1))
    }

    companion object {
        const val ACTION_POSTURE_ALERT = "com.local.guardian.POSTURE_ALERT"
        const val EXTRA_POSTURE = "posture"
        private const val REOPEN_WINDOW_MS = 60_000L
        private const val EXIT_CONFIRM_MS = 3_000L
    }
}
