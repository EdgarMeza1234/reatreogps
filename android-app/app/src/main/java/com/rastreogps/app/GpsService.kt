package com.rastreogps.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class GpsService : Service() {

    private lateinit var fused: FusedLocationProviderClient
    private var lastPostMs = 0L
    private val minIntervalMs: Long = 30_000

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result?.lastLocation ?: return
            val now = System.currentTimeMillis()
            if (now - lastPostMs < minIntervalMs) return
            lastPostMs = now
            postAsync(loc)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startUpdates()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TimeUnit.SECONDS.toMillis(20))
            .setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(10))
            .build()
        try {
            fused.requestLocationUpdates(request, callback, null)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun postAsync(loc: Location) {
        val prefs = getSharedPreferences("rastreogps", Context.MODE_PRIVATE)
        val dev = prefs.getString("device", "").orEmpty().trim()
        val urlBase = prefs.getString("base_url", "").orEmpty().trim().trimEnd('/')
        if (urlBase.isEmpty() || dev.isEmpty()) return
        val ok = try {
            val obj = JSONObject()
                .put("device", dev)
                .put("lat", loc.latitude)
                .put("lng", loc.longitude)
                .put("acc", loc.accuracy)
            val url = URL("$urlBase/api/points")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(obj.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
        if (!ok) lastPostMs = 0
    }

    override fun onDestroy() {
        try {
            fused.removeLocationUpdates(callback)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val dev = getSharedPreferences("rastreogps", Context.MODE_PRIVATE)
            .getString("device", "").orEmpty().trim()
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, GpsService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Rastreo GPS activo")
            .setContentText(dev.ifEmpty { "Reportando ubicacion" })
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 26) {
            b.addAction(0, "Detener", stop)
        }
        return b.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Rastreo GPS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "rastreogps"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "STOP"
    }
}