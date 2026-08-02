package com.local.guardian

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Suppress("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var selectedAppsSummary: TextView
    private var selectedPackages = mutableSetOf<String>()
    private lateinit var minutes: EditText
    private lateinit var scrolls: EditText
    private lateinit var distanceSeconds: EditText
    private lateinit var postureSeconds: EditText
    private lateinit var postureRepeatSeconds: EditText
    private lateinit var doomRepeatSeconds: EditText
    private lateinit var distanceButtonText: EditText
    private lateinit var postureTitleText: EditText
    private lateinit var postureButtonText: EditText
    private lateinit var doomButtonText: EditText
    private lateinit var studyStartText: Button
    private lateinit var studyEndText: Button
    private lateinit var sleepStartText: Button
    private lateinit var sleepEndText: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun buildUi(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(34), dp(22), dp(40))
            setBackgroundColor(Color.rgb(244, 240, 232))
        }

        root.addView(TextView(this).apply {
            text = "守护"
            textSize = 34f
            setTextColor(Color.rgb(31, 49, 40))
        })
        root.addView(TextView(this).apply {
            text = "打断侧躺刷手机和无意识滚动。所有检测均在本机完成，不保存相机画面。"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(20))
        })

        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(36, 76, 57))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(Color.rgb(224, 235, 226))
        }
        root.addView(status, matchWrap())

        root.addView(section("第一步：开启滚动检测"))
        root.addView(button("打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(section("第二步：开始姿势守护"))
        root.addView(button("开始守护") { startGuardian() })
        root.addView(button("停止姿势守护") {
            stopService(Intent(this, PoseMonitorService::class.java))
            Toast.makeText(this, "姿势守护已停止", Toast.LENGTH_SHORT).show()
            updateStatus()
        })

        root.addView(section("目标 App"))
        selectedPackages = GuardianPrefs.targetPackages(this).toMutableSet()
        selectedAppsSummary = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        root.addView(selectedAppsSummary, matchWrap())
        updateSelectedAppsSummary()
        root.addView(button("选择目标 App") { showAppPicker() })

        root.addView(section("Doom scrolling 检测"))
        minutes = numericField("连续使用分钟数", GuardianPrefs.riskMinutes(this))
        scrolls = numericField("最近一分钟滚动次数", GuardianPrefs.riskScrolls(this))
        doomRepeatSeconds = numericField("重复提醒秒数", GuardianPrefs.doomRepeatSeconds(this))
        root.addView(numberRow("连续使用", minutes, "分钟"), matchWrap())
        root.addView(numberRow("最近一分钟滚动", scrolls, "次/分钟"), matchWrap())
        root.addView(numberRow("重复提醒", doomRepeatSeconds, "秒"), matchWrap())

        root.addView(section("距离警报"))
        distanceSeconds = numericField("触发秒数", GuardianPrefs.distanceConfirmSeconds(this))
        distanceButtonText = textField("按钮文字", GuardianPrefs.distanceButton(this))
        root.addView(numberRow("触发时间", distanceSeconds, "秒"), matchWrap())
        root.addView(textRow("按钮", distanceButtonText), matchWrap())

        root.addView(section("姿势警报"))
        postureSeconds = numericField("触发秒数", GuardianPrefs.postureConfirmSeconds(this))
        postureRepeatSeconds = numericField("距离/姿势重复提醒秒数", GuardianPrefs.postureRepeatSeconds(this))
        postureTitleText = textField("标题", GuardianPrefs.postureTitle(this))
        postureButtonText = textField("按钮文字", GuardianPrefs.postureButton(this))
        root.addView(numberRow("触发时间", postureSeconds, "秒"), matchWrap())
        root.addView(numberRow("距离/姿势重复提醒", postureRepeatSeconds, "秒"), matchWrap())
        root.addView(textRow("标题", postureTitleText), matchWrap())
        root.addView(textRow("按钮", postureButtonText), matchWrap())

        root.addView(section("学习与睡眠时段（HH:mm）"))
        studyStartText = timeButton(GuardianPrefs.studyStart(this))
        studyEndText = timeButton(GuardianPrefs.studyEnd(this))
        sleepStartText = timeButton(GuardianPrefs.sleepStart(this))
        sleepEndText = timeButton(GuardianPrefs.sleepEnd(this))
        doomButtonText = textField("Doom 提醒按钮文字", GuardianPrefs.doomButton(this))
        root.addView(timeRow("学习时段", studyStartText, studyEndText), matchWrap())
        root.addView(timeRow("睡眠时段", sleepStartText, sleepEndText), matchWrap())
        root.addView(textRow("Doom 按钮", doomButtonText), matchWrap())

        root.addView(button("保存设置") {
            GuardianPrefs.save(
                this,
                selectedPackages.joinToString("\n"),
                minutes.text.toString().toIntOrNull() ?: 5,
                scrolls.text.toString().toIntOrNull() ?: 6,
                distanceSeconds.text.toString().toIntOrNull() ?: 10,
                postureSeconds.text.toString().toIntOrNull() ?: 20,
                postureRepeatSeconds.text.toString().toIntOrNull() ?: 60,
                doomRepeatSeconds.text.toString().toIntOrNull() ?: 120,
                distanceButtonText.text.toString(),
                postureTitleText.text.toString(),
                postureButtonText.text.toString(),
                doomButtonText.text.toString(),
                studyStartText.text.toString(),
                studyEndText.text.toString(),
                sleepStartText.text.toString(),
                sleepEndText.text.toString(),
            )
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
        })

        root.addView(TextView(this).apply {
            text = "提示：首次使用先点“开始守护”，看到常驻通知后再切换到抖音或小红书。前摄正在工作时，OriginOS 会显示系统隐私指示。"
            textSize = 14f
            setTextColor(Color.GRAY)
            setPadding(0, dp(24), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun startGuardian() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
            return
        }
        launchPoseService()
    }

    private fun launchPoseService() {
        ContextCompat.startForegroundService(this, Intent(this, PoseMonitorService::class.java))
        Toast.makeText(this, "姿势守护已启动，可以切换到目标 App", Toast.LENGTH_LONG).show()
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchPoseService()
        }
    }

    private fun updateStatus() {
        val accessibility = isAccessibilityEnabled()
        val posture = GuardianPrefs.posture(this)
        status.text = buildString {
            append(if (accessibility) "✓ 滚动检测已开启" else "○ 尚未开启滚动检测")
            append("\n最近姿势：")
            append(postureLabel(posture))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, GuardianAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
    }

    private fun postureLabel(posture: PostureState) = when (posture) {
        PostureState.UPRIGHT -> "正常"
        PostureState.LEFT_SIDE -> "左侧躺"
        PostureState.RIGHT_SIDE -> "右侧躺"
        PostureState.TOO_CLOSE -> "距离过近"
        PostureState.UNKNOWN -> "暂未识别"
    }

    private fun numericField(hintText: String, value: Int) = EditText(this).apply {
        hint = hintText
        setText(value.toString())
        inputType = InputType.TYPE_CLASS_NUMBER
        setBackgroundColor(Color.WHITE)
        setPadding(18, 14, 18, 14)
    }

    private fun textField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT
        setBackgroundColor(Color.WHITE)
        setPadding(18, 14, 18, 14)
    }

    private fun numberRow(label: String, field: EditText, unit: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(field, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(TextView(this@MainActivity).apply {
            text = unit
            setPadding(dp(10), 0, 0, 0)
        })
    }

    private fun textRow(label: String, field: EditText) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
    }

    private fun timeRow(label: String, start: Button, end: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply { text = label }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(start)
        addView(TextView(this@MainActivity).apply {
            text = "至"
            setPadding(dp(8), 0, dp(8), 0)
        })
        addView(end)
    }

    private fun timeButton(value: String): Button = Button(this).apply {
        text = value
        isAllCaps = false
        setOnClickListener {
            val initial = runCatching { LocalTime.parse(text.toString(), TIME_FORMAT) }.getOrDefault(LocalTime.NOON)
            TimePickerDialog(
                this@MainActivity,
                { _, hour, minute -> text = LocalTime.of(hour, minute).format(TIME_FORMAT) },
                initial.hour,
                initial.minute,
                true,
            ).show()
        }
    }

    private fun showAppPicker() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        val pending = selectedPackages.toMutableSet()
        val labels = apps.map { (label, packageName) -> "$label\n$packageName" }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it].second in pending }
        AlertDialog.Builder(this)
            .setTitle("选择需要守护的 App")
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                val packageName = apps[index].second
                if (isChecked) pending += packageName else pending -= packageName
            }
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ ->
                selectedPackages = pending
                updateSelectedAppsSummary()
            }
            .show()
    }

    private fun updateSelectedAppsSummary() {
        selectedAppsSummary.text = if (selectedPackages.isEmpty()) {
            "尚未选择目标 App"
        } else {
            selectedPackages.map { packageName ->
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
            }.sorted().joinToString("、")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun section(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTextColor(Color.rgb(31, 49, 40))
        setPadding(0, 28, 0, 10)
    }

    private fun button(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        setOnClickListener { action() }
        isAllCaps = false
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
    }
}
