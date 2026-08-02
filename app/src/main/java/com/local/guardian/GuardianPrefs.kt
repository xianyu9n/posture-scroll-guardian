package com.local.guardian

import android.content.Context
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object GuardianPrefs {
    private const val FILE = "guardian"
    private const val KEY_PACKAGES = "target_packages"
    private const val KEY_MINUTES = "risk_minutes"
    private const val KEY_SCROLLS = "risk_scrolls_per_minute"
    private const val KEY_DISTANCE_SECONDS = "distance_confirm_seconds"
    private const val KEY_POSTURE_SECONDS = "posture_confirm_seconds"
    private const val KEY_POSTURE_REPEAT_SECONDS = "posture_repeat_seconds"
    private const val KEY_DOOM_REPEAT_SECONDS = "doom_repeat_seconds"
    private const val KEY_DISTANCE_BUTTON = "distance_button"
    private const val KEY_POSTURE_TITLE = "posture_title"
    private const val KEY_POSTURE_BUTTON = "posture_button"
    private const val KEY_DOOM_BUTTON = "doom_button"
    private const val KEY_STUDY_START = "study_start"
    private const val KEY_STUDY_END = "study_end"
    private const val KEY_SLEEP_START = "sleep_start"
    private const val KEY_SLEEP_END = "sleep_end"
    const val KEY_POSTURE = "posture"
    const val KEY_POSTURE_UPDATED_AT = "posture_updated_at"

    const val DEFAULT_PACKAGES = ""

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun packagesText(context: Context): String =
        prefs(context).getString(KEY_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES

    fun targetPackages(context: Context): Set<String> = packagesText(context)
        .split(',', '\n', ';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    fun riskMinutes(context: Context): Int =
        prefs(context).getInt(KEY_MINUTES, 5).coerceIn(1, 60)

    fun riskScrolls(context: Context): Int =
        prefs(context).getInt(KEY_SCROLLS, 6).coerceIn(2, 120)

    fun distanceConfirmSeconds(context: Context) = prefs(context).getInt(KEY_DISTANCE_SECONDS, 10).coerceIn(3, 120)
    fun postureConfirmSeconds(context: Context) = prefs(context).getInt(KEY_POSTURE_SECONDS, 20).coerceIn(3, 120)
    fun postureRepeatSeconds(context: Context) = prefs(context).getInt(KEY_POSTURE_REPEAT_SECONDS, 60).coerceIn(10, 3600)
    fun doomRepeatSeconds(context: Context) = prefs(context).getInt(KEY_DOOM_REPEAT_SECONDS, 120).coerceIn(10, 3600)
    fun distanceButton(context: Context) = prefs(context).getString(KEY_DISTANCE_BUTTON, "知道啦~") ?: "知道啦~"
    fun postureTitle(context: Context) = prefs(context).getString(KEY_POSTURE_TITLE, "注意姿势哟~") ?: "注意姿势哟~"
    fun postureButton(context: Context) = prefs(context).getString(KEY_POSTURE_BUTTON, "好哒~") ?: "好哒~"
    fun doomButton(context: Context) = prefs(context).getString(KEY_DOOM_BUTTON, "知道啦~") ?: "知道啦~"
    fun studyStart(context: Context) = prefs(context).getString(KEY_STUDY_START, "19:00") ?: "19:00"
    fun studyEnd(context: Context) = prefs(context).getString(KEY_STUDY_END, "22:30") ?: "22:30"
    fun sleepStart(context: Context) = prefs(context).getString(KEY_SLEEP_START, "23:00") ?: "23:00"
    fun sleepEnd(context: Context) = prefs(context).getString(KEY_SLEEP_END, "07:00") ?: "07:00"

    fun save(
        context: Context,
        packages: String,
        minutes: Int,
        scrolls: Int,
        distanceSeconds: Int,
        postureSeconds: Int,
        postureRepeatSeconds: Int,
        doomRepeatSeconds: Int,
        distanceButton: String,
        postureTitle: String,
        postureButton: String,
        doomButton: String,
        studyStart: String,
        studyEnd: String,
        sleepStart: String,
        sleepEnd: String,
    ) {
        prefs(context).edit()
            .putString(KEY_PACKAGES, packages)
            .putInt(KEY_MINUTES, minutes.coerceIn(1, 60))
            .putInt(KEY_SCROLLS, scrolls.coerceIn(2, 120))
            .putInt(KEY_DISTANCE_SECONDS, distanceSeconds.coerceIn(3, 120))
            .putInt(KEY_POSTURE_SECONDS, postureSeconds.coerceIn(3, 120))
            .putInt(KEY_POSTURE_REPEAT_SECONDS, postureRepeatSeconds.coerceIn(10, 3600))
            .putInt(KEY_DOOM_REPEAT_SECONDS, doomRepeatSeconds.coerceIn(10, 3600))
            .putString(KEY_DISTANCE_BUTTON, distanceButton.ifBlank { "知道啦~" })
            .putString(KEY_POSTURE_TITLE, postureTitle.ifBlank { "注意姿势哟~" })
            .putString(KEY_POSTURE_BUTTON, postureButton.ifBlank { "好哒~" })
            .putString(KEY_DOOM_BUTTON, doomButton.ifBlank { "知道啦~" })
            .putString(KEY_STUDY_START, validTimeOr(studyStart, "19:00"))
            .putString(KEY_STUDY_END, validTimeOr(studyEnd, "22:30"))
            .putString(KEY_SLEEP_START, validTimeOr(sleepStart, "23:00"))
            .putString(KEY_SLEEP_END, validTimeOr(sleepEnd, "07:00"))
            .apply()
    }

    fun isProtectedTime(context: Context, now: LocalTime = LocalTime.now()): Boolean =
        inWindow(now, studyStart(context), studyEnd(context)) ||
            inWindow(now, sleepStart(context), sleepEnd(context))

    private fun inWindow(now: LocalTime, startText: String, endText: String): Boolean {
        val start = LocalTime.parse(startText, TIME_FORMAT)
        val end = LocalTime.parse(endText, TIME_FORMAT)
        return if (start <= end) now >= start && now < end else now >= start || now < end
    }

    private fun validTimeOr(value: String, fallback: String): String = runCatching {
        LocalTime.parse(value.trim(), TIME_FORMAT).format(TIME_FORMAT)
    }.getOrDefault(fallback)

    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

    fun savePosture(context: Context, posture: PostureState) {
        prefs(context).edit()
            .putString(KEY_POSTURE, posture.name)
            .putLong(KEY_POSTURE_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun savePoseDiagnostics(
        context: Context,
        faceRoll: Float,
        deviceRoll: Float,
        rawFaceRatio: Float,
        rotatedFaceRatio: Float,
        rotationDegrees: Int,
        imageWidth: Int,
        imageHeight: Int,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
    ) {
        prefs(context).edit()
            .putFloat("debug_pose_face_roll", faceRoll)
            .putFloat("debug_pose_device_roll", deviceRoll)
            .putFloat("debug_pose_raw_ratio", rawFaceRatio)
            .putFloat("debug_pose_rotated_ratio", rotatedFaceRatio)
            .putInt("debug_pose_rotation", rotationDegrees)
            .putInt("debug_pose_image_width", imageWidth)
            .putInt("debug_pose_image_height", imageHeight)
            .putFloat("debug_pose_gravity_x", gravityX)
            .putFloat("debug_pose_gravity_y", gravityY)
            .putFloat("debug_pose_gravity_z", gravityZ)
            .apply()
    }

    fun posture(context: Context): PostureState = runCatching {
        PostureState.valueOf(prefs(context).getString(KEY_POSTURE, PostureState.UNKNOWN.name)!!)
    }.getOrDefault(PostureState.UNKNOWN)
}
