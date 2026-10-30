package com.example.h848connect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvDevice: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var swEnabled: SwitchMaterial
    private lateinit var swProbe: SwitchMaterial

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1500)
        }
    }

    private var afterPerms: (() -> Unit)? = null
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (Perms.hasBluetooth(this)) {
                afterPerms?.invoke()
            } else {
                toast(getString(R.string.need_bt_perms))
            }
            afterPerms = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDevice = findViewById(R.id.tvDevice)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        swEnabled = findViewById(R.id.swEnabled)
        swProbe = findViewById(R.id.swProbe)

        findViewById<MaterialButton>(R.id.btnPick).setOnClickListener {
            ensurePerms { pickDevice() }
        }

        swEnabled.setOnClickListener {
            if (swEnabled.isChecked) {
                if (Store.address(this) == null) {
                    swEnabled.isChecked = false
                    toast(getString(R.string.pick_first))
                } else {
                    ensurePerms {
                        Store.setEnabled(this, true)
                        DetectorService.start(this)
                        refresh()
                    }
                }
            } else {
                Store.setEnabled(this, false)
                DetectorService.stop(this)
            }
        }

        swProbe.setOnClickListener {
            Store.setProbe(this, swProbe.isChecked)
            restartIfEnabled()
        }

        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }

        findViewById<MaterialButton>(R.id.btnBattery).setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        findViewById<MaterialButton>(R.id.btnXiaomi).setOnClickListener {
            val options = listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                ),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            )
            for (intent in options) {
                try {
                    startActivity(intent)
                    break
                } catch (e: Exception) {
                    // probar la siguiente opción
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener {
            if (!Store.enabled(this) || !DetectorService.running) {
                toast(getString(R.string.need_enable))
            } else {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, DetectorService::class.java).setAction(DetectorService.ACTION_TEST),
                )
            }
        }

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            EventLog.clear(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Store.enabled(this) && !DetectorService.running &&
            Perms.hasBluetooth(this) && Store.address(this) != null
        ) {
            DetectorService.start(this)
        }
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun ensurePerms(then: () -> Unit) {
        val missing = Perms.required().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            then()
        } else {
            afterPerms = then
            permLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            toast(getString(R.string.turn_on_bt))
            return
        }
        val bonded = adapter.bondedDevices.toList().sortedBy { if (looksLikeH848(it)) 0 else 1 }
        if (bonded.isEmpty()) {
            toast(getString(R.string.no_bonded))
            return
        }
        val labels = bonded.map { "${it.name ?: "Sin nombre"}  (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_title)
            .setItems(labels) { _, which ->
                val d = bonded[which]
                Store.setDevice(this, d.address, d.name ?: d.address)
                EventLog.add(this, "Elegiste: ${d.name} (${d.address})")
                restartIfEnabled()
                refresh()
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun looksLikeH848(d: BluetoothDevice): Boolean {
        val n = d.name ?: return false
        return n.contains("redragon", ignoreCase = true) || n.contains("h848", ignoreCase = true)
    }

    private fun restartIfEnabled() {
        if (!Store.enabled(this)) return
        DetectorService.stop(this)
        handler.postDelayed({
            if (Perms.hasBluetooth(this) && Store.address(this) != null) DetectorService.start(this)
        }, 600)
    }

    private fun refresh() {
        val addr = Store.address(this)
        tvDevice.text =
            if (addr == null) getString(R.string.no_device) else "${Store.name(this)}\n$addr"
        swEnabled.isChecked = Store.enabled(this)
        swProbe.isChecked = Store.probe(this)

        val btOn = getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val overlay = Settings.canDrawOverlays(this)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        tvStatus.text = listOf(
            mark(btOn) + " Bluetooth prendido",
            mark(Perms.hasBluetooth(this)) + " Permiso de Bluetooth",
            mark(overlay) + " Mostrar sobre otras apps",
            mark(battery) + " Sin restricción de batería",
            mark(DetectorService.running) + " Detección corriendo",
        ).joinToString("\n")

        val lines = EventLog.read(this).split("\n").filter { it.isNotBlank() }.reversed()
        tvLog.text = if (lines.isEmpty()) getString(R.string.log_empty) else lines.joinToString("\n")
    }

    private fun mark(ok: Boolean) = if (ok) "✅" else "⚠️"

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
