package com.local.guardian

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.atan2

class PoseMonitorService : LifecycleService(), SensorEventListener {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.18f)
            .build(),
    )
    private lateinit var sensorManager: SensorManager
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var deviceRoll = Float.NaN
    @Volatile private var gravityX = Float.NaN
    @Volatile private var gravityY = Float.NaN
    @Volatile private var gravityZ = Float.NaN
    private var lastAnalysisAt = 0L
    private var candidate = PostureState.UNKNOWN
    private var candidateSince = 0L
    private var lastAlertAt = 0L
    private var lastAlertedPosture = PostureState.UNKNOWN

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("正在本机检测姿势"))
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        startCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor, ::analyze)
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {
                GuardianPrefs.savePosture(this, PostureState.UNKNOWN)
                updateNotification("前置摄像头启动失败，请重新开始守护")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastAnalysisAt = now
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val imageWidth = proxy.width
        val imageHeight = proxy.height
        val rotationDegrees = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val posture = if (face == null || !deviceRoll.isFinite()) {
                    PostureState.UNKNOWN
                } else {
                    val rawRatio = face.boundingBox.width() / imageWidth.toFloat()
                    val rotatedWidth = if (rotationDegrees % 180 == 0) imageWidth else imageHeight
                    val rotatedRatio = face.boundingBox.width() / rotatedWidth.toFloat()
                    GuardianPrefs.savePoseDiagnostics(
                        this,
                        face.headEulerAngleZ,
                        deviceRoll,
                        rawRatio,
                        rotatedRatio,
                        rotationDegrees,
                        imageWidth,
                        imageHeight,
                        gravityX,
                        gravityY,
                        gravityZ,
                    )
                    PostureClassifier.classify(
                        faceRollDegrees = face.headEulerAngleZ,
                        deviceRollDegrees = deviceRoll,
                        faceWidthRatio = rawRatio,
                    )
                }
                record(posture)
            }
            .addOnFailureListener { record(PostureState.UNKNOWN) }
            .addOnCompleteListener { proxy.close() }
    }

    private fun record(posture: PostureState) {
        GuardianPrefs.savePosture(this, posture)
        updateNotification("最近姿势：${postureLabel(posture)}")
        val now = System.currentTimeMillis()
        if (!posture.isRisky) {
            candidate = posture
            candidateSince = now
            lastAlertedPosture = PostureState.UNKNOWN
            return
        }
        if (candidate != posture) {
            candidate = posture
            candidateSince = now
            return
        }
        val confirmMillis = if (posture == PostureState.TOO_CLOSE) {
            GuardianPrefs.distanceConfirmSeconds(this) * 1_000L
        } else {
            GuardianPrefs.postureConfirmSeconds(this) * 1_000L
        }
        val repeatMillis = GuardianPrefs.postureRepeatSeconds(this) * 1_000L
        if (now - candidateSince >= confirmMillis && PostureAlertPolicy.shouldAlert(
                posture = posture,
                lastAlertedPosture = lastAlertedPosture,
                now = now,
                lastAlertAt = lastAlertAt,
                cooldownMillis = repeatMillis,
            )
        ) {
            lastAlertAt = now
            lastAlertedPosture = posture
            sendBroadcast(
                Intent(GuardianAccessibilityService.ACTION_POSTURE_ALERT)
                    .setPackage(packageName)
                    .putExtra(GuardianAccessibilityService.EXTRA_POSTURE, posture.name),
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            gravityX = event.values[0]
            gravityY = event.values[1]
            gravityZ = event.values[2]
            deviceRoll = Math.toDegrees(atan2(gravityX, gravityY).toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        detector.close()
        cameraExecutor.shutdown()
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "姿势守护", NotificationManager.IMPORTANCE_LOW).apply {
                description = "前置摄像头仅在本机分析头部姿势"
            },
        )
    }

    private fun notification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("守护运行中")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun postureLabel(posture: PostureState) = when (posture) {
        PostureState.UPRIGHT -> "正常"
        PostureState.LEFT_SIDE -> "左侧躺"
        PostureState.RIGHT_SIDE -> "右侧躺"
        PostureState.TOO_CLOSE -> "距离可能过近"
        PostureState.UNKNOWN -> "未识别到脸部"
    }

    companion object {
        private const val CHANNEL_ID = "guardian_pose"
        private const val NOTIFICATION_ID = 1001
        private const val ANALYSIS_INTERVAL_MS = 3_000L
    }
}
