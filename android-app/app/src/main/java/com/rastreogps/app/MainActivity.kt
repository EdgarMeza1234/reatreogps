package com.rastreogps.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("rastreogps", Context.MODE_PRIVATE)
        nameInput = findViewById(R.id.name)
        urlInput = findViewById(R.id.url)
        nameInput.setText(prefs.getString("device", ""))
        urlInput.setText(prefs.getString("base_url", "https://comacotap.tail1188d3.ts.net/rastreogps"))

        val btn = findViewById<Button>(R.id.btn)
        val status = findViewById<TextView>(R.id.status)

        btn.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim().trimEnd('/')
            if (isRunning()) {
                stopService(Intent(this, GpsService::class.java))
                setRunning(false)
                btn.text = "Iniciar rastreo"
                status.text = "Detenido. Se apaga el GPS."
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                status.text = "Ponle un nombre (ej: Edgar)."
                return@setOnClickListener
            }
            prefs.edit().putString("device", name).putString("base_url", url).apply()
            if (!hasPermissions()) {
                requestPermissions()
                status.text = "Acepta los permisos de ubicacion."
            } else {
                startTracking(btn, status)
            }
        }

        updateUi(btn, status)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (hasPermissions()) {
                val btn = findViewById<Button>(R.id.btn)
                startTracking(btn, findViewById(R.id.status))
            } else {
                findViewById<TextView>(R.id.status).text =
                    "Sin permisos no se puede rastrear. Vuelve a tocar INICIAR."
            }
        }
    }

    private fun startTracking(btn: Button, status: TextView) {
        ContextCompat.startForegroundService(this, Intent(this, GpsService::class.java))
        setRunning(true)
        askBatteryExemption()
        updateUi(btn, status)
        status.text = "Rastreando en segundo plano. Puedes bloquear el celular o usar otras apps."
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun updateUi(btn: Button, status: TextView) {
        btn.text = if (isRunning()) "Detener rastreo" else "Iniciar rastreo"
        if (isRunning()) status.text = "Rastreando en segundo plano..."
    }

    private fun hasPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED) return false
        return bg == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 30) perms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    private fun isRunning(): Boolean = prefs.getBoolean("running", false)
    private fun setRunning(v: Boolean) = prefs.edit().putBoolean("running", v).apply()

    companion object {
        fun isEnabled(c: Context): Boolean =
            c.getSharedPreferences("rastreogps", Context.MODE_PRIVATE).getBoolean("running", false)
    }
}