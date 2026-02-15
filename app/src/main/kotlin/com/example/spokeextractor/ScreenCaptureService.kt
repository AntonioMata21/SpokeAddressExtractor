package com.example.spokeextractor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.IOException

class ScreenCaptureService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var resultCode: Int = 0
    private var resultData: Intent? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "ScreenCaptureChannel"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "ScreenCaptureService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA)
            
            if (resultCode != 0 && resultData != null) {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData!!)
                setupImageReader()
                createVirtualDisplay()
            }
        } else if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupOverlay() {
        overlayView = LayoutInflater.from(this).inflate(R.layout.layout_overlay, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        val btnCapture = overlayView?.findViewById<ImageButton>(R.id.btn_capture)
        btnCapture?.setOnClickListener {
            captureAndProcess()
        }
        
        // Setup drag listener (simplified)
        overlayView?.setOnTouchListener(object : View.OnTouchListener {
             // Basic drag implementation would go here
             // For brevity, skipping full drag logic
             override fun onTouch(v: View?, event: android.view.MotionEvent?): Boolean {
                 return false
             }
        })

        windowManager?.addView(overlayView, params)
    }

    private fun setupImageReader() {
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        
        // Use ImageFormat.RGBA_8888 for better compatibility with ML Kit
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    }

    private fun createVirtualDisplay() {
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun captureAndProcess() {
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "Capturando pantalla...", Toast.LENGTH_SHORT).show()
            }
            
            try {
                // Intentamos obtener la imagen. A veces acquireLatestImage es null si la pantalla no ha cambiado.
                // Reintentamos un par de veces con un pequeño delay.
                var image = imageReader?.acquireLatestImage()
                if (image == null) {
                    delay(100)
                    image = imageReader?.acquireLatestImage()
                }

                if (image != null) {
                    val bitmap = image.planes[0].let { plane ->
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val width = image.width
                        val height = image.height
                        
                        // Ajuste para rowPadding
                        val rowPadding = rowStride - pixelStride * width
                        val bitmap = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        
                        // Recortar si hubo padding
                        if (rowPadding > 0) {
                            Bitmap.createBitmap(bitmap, 0, 0, width, height)
                        } else {
                            bitmap
                        }
                    }
                    
                    image.close()
                    processImage(bitmap)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "Error: No se pudo obtener imagen (pantalla estática?)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en captura", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Error técnico: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun processImage(bitmap: Bitmap) {
        withContext(Dispatchers.Main) {
            Toast.makeText(applicationContext, "Procesando OCR...", Toast.LENGTH_SHORT).show()
        }
        
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                if (text.isNotBlank()) {
                    parseAndSaveAddress(text)
                } else {
                    Toast.makeText(applicationContext, "No se detectó texto en la pantalla", Toast.LENGTH_SHORT).show()
                }
                bitmap.recycle()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR failed", e)
                Toast.makeText(applicationContext, "Error en OCR", Toast.LENGTH_SHORT).show()
                bitmap.recycle()
            }
    }

    private fun parseAndSaveAddress(text: String) {
        // Simple Regex for demonstration - needs refinement based on actual app data
        // Matches typical address patterns: "123 Main St, City, 12345"
        // This is a placeholder logic
        
        val lines = text.split("\n")
        var addressLine1 = ""
        var zip = ""
        var city = ""
        var notes = ""

        // Example Logic: Look for Zip Code pattern (5 digits)
        val zipRegex = Regex("\\b\\d{5}\\b")
        
        for (line in lines) {
            if (zipRegex.containsMatchIn(line)) {
                zip = zipRegex.find(line)?.value ?: ""
                // Assume line containing zip might also have city
                city = line.replace(zip, "").trim().trim(',')
            } else if (line.matches(Regex(".*\\d+.*"))) {
                // Assume line with numbers is address line 1 if not zip
                if (addressLine1.isEmpty()) addressLine1 = line
            } else {
                notes += "$line "
            }
        }

        val csvLine = "\"$addressLine1\",\"$zip\",\"$city\",\"$notes\""
        saveToCsv(csvLine)
        
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "Address Saved: $addressLine1", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToCsv(line: String) {
        try {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SpokeExports")
            if (!folder.exists()) folder.mkdirs()
            
            val file = File(folder, "addresses.csv")
            val isNew = !file.exists()
            
            FileWriter(file, true).use { writer ->
                if (isNew) {
                    writer.append("Address Line 1,Zip/Postal Code,City,Notes\n")
                }
                writer.append("$line\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing CSV", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spoke Address Extractor")
            .setContentText("Service is running with overlay")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        if (overlayView != null) {
            windowManager?.removeView(overlayView)
        }
        serviceScope.cancel()
    }
}
