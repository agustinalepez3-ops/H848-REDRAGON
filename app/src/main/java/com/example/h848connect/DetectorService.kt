package com.example.h848connect

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

/**
 * Servicio en primer plano que vigila tus auriculares.
 *
 * Detecta que se prendieron por dos caminos:
 *  1. Escaneo Bluetooth LE filtrado por la dirección/nombre de los auriculares (casi sin batería).
 *  2. "Búsqueda activa" opcional: cada 30 s intenta abrir un enlace corto con ellos; si responden, están prendidos.
 *
 * Cuando los ve prendidos y sin audio conectado, muestra la ventana desde abajo.
 */
@SuppressLint("MissingPermission")
class DetectorService : Service() {

    companion object {
        const val ACTION_CONNECT = "com.example.h848connect.CONNECT"
        const val ACTION_DISMISS = "com.example.h848connect.DISMISS"
        const val ACTION_TEST = "com.example.h848connect.TEST"

        private const val CH_SERVICE = "service"
        private const val CH_PROMPT = "prompt"
        private const val ID_SERVICE = 1
        private const val ID_PROMPT = 2
        private const val ID_RESULT = 3

        private const val GAP_MS = 60_000L
        private const val PROBE_EVERY_MS = 30_000L
        private const val SETTLE_MS = 3_000L

        private val HFP_UUID: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")

        @Volatile
        var running = false

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DetectorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, DetectorService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var adapter: BluetoothAdapter? = null
    private var address = ""
    private var name = ""
    private var started = false

    private var scanCb: ScanCallback? = null
    private var a2dp: BluetoothProfile? = null
    private var headset: BluetoothProfile? = null
    private var prompt: BottomPrompt? = null
    private var probeThread: Thread? = null

    private var lastSeen = 0L
    private var promptedThisRound = false

    @Volatile
    private var connecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Perms.hasBluetooth(this) || Store.address(this) == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                ID_SERVICE,
                serviceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            log("No pude iniciar el servicio: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!started) {
            started = true
            setup()
        }

        when (intent?.action) {
            ACTION_CONNECT -> {
                cancelPrompt()
                startConnect()
            }
            ACTION_DISMISS -> cancelPrompt()
            ACTION_TEST -> main.post { showPrompt() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        probeThread?.interrupt()
        stopScan()
        try {
            unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // no estaba registrado
        }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        prompt?.dismiss()
        log("Detección detenida")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- arranque

    private fun setup() {
        address = Store.address(this) ?: return
        name = Store.name(this)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        adapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)

        log("Vigilando $name ($address)")
        startScan()
        if (Store.probe(this)) startProbe()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) a2dp = proxy
            if (profile == BluetoothProfile.HEADSET) headset = proxy
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
            if (profile == BluetoothProfile.HEADSET) headset = null
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
                            log("Bluetooth apagado: detección en pausa")
                            stopScan()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            log("Bluetooth prendido: detección activa")
                            startScan()
                        }
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (!isTarget(i)) return
                    when (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            log("Audio conectado ✓")
                            promptedThisRound = true
                            prompt?.dismiss()
                            cancelPrompt()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            log("Audio desconectado")
                            lastSeen = 0L
                            promptedThisRound = false
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private fun isTarget(i: Intent): Boolean =
        deviceFrom(i)?.address.equals(address, ignoreCase = true)

    // ---------------------------------------------------------------- detección

    private fun startScan() {
        if (scanCb != null) return
        val ad = adapter
        if (ad == null || !ad.isEnabled) {
            log("Escaneo no iniciado: Bluetooth apagado")
            return
        }
        val scanner = ad.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onSeen("BLE")
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) onSeen("BLE")
            }

            override fun onScanFailed(errorCode: Int) {
                log("Falló el escaneo BLE (código $errorCode)")
                scanCb = null
            }
        }
        try {
            val filters = mutableListOf(ScanFilter.Builder().setDeviceAddress(address).build())
            if (name.isNotBlank()) filters.add(ScanFilter.Builder().setDeviceName(name).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            scanCb = cb
            scanner.startScan(filters, settings, cb)
            log("Escaneo BLE activo")
        } catch (e: Exception) {
            scanCb = null
            log("Error al iniciar escaneo: ${e.message}")
        }
    }

    private fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: Exception) {
            // ignorar
        }
    }

    private fun startProbe() {
        if (probeThread != null) return
        probeThread = Thread {
            log("Búsqueda activa encendida")
            try {
                while (running) {
                    if (adapter?.isEnabled == true && !connecting && !audioConnected()) {
                        val socket = openLink()
                        if (socket != null) {
                            closeQuietly(socket)
                            main.post { onSeen("búsqueda activa") }
                        }
                    }
                    Thread.sleep(PROBE_EVERY_MS)
                }
            } catch (e: InterruptedException) {
                // servicio detenido
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Se llama (en el hilo principal) cada vez que se ve a los auriculares. */
    private fun onSeen(source: String) {
        val now = SystemClock.elapsedRealtime()
        val fresh = now - lastSeen > GAP_MS
        lastSeen = now
        if (fresh) {
            promptedThisRound = false
            log("Auriculares detectados ($source)")
        }
        if (promptedThisRound || connecting) return
        if (audioConnected()) return
        promptedThisRound = true
        // Esperamos unos segundos: si el celu los conecta solo, no molestamos.
        main.postDelayed({
            if (!running) return@postDelayed
            if (audioConnected()) {
                log("Se conectaron solos, no muestro nada")
            } else {
                showPrompt()
            }
        }, SETTLE_MS)
    }

    private fun connectedTo(p: BluetoothProfile?): Boolean =
        try {
            p?.connectedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
        } catch (e: Exception) {
            false
        }

    private fun audioConnected(): Boolean = connectedTo(a2dp) || connectedTo(headset)

    // ---------------------------------------------------------------- aviso

    private fun showPrompt() {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (!locked && Settings.canDrawOverlays(this)) {
            prompt?.dismiss()
            val p = BottomPrompt(
                this,
                name,
                onConnect = { startConnect() },
                onDismissed = { log("Dijiste que no") },
            )
            if (p.show()) {
                prompt = p
                log("Ventana mostrada")
                return
            }
        }
        postPromptNotification()
    }

    private fun postPromptNotification() {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val connect = PendingIntent.getService(
            this, 1, Intent(this, DetectorService::class.java).setAction(ACTION_CONNECT), flags,
        )
        val no = PendingIntent.getService(
            this, 2, Intent(this, DetectorService::class.java).setAction(ACTION_DISMISS), flags,
        )
        val n = NotificationCompat.Builder(this, CH_PROMPT)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(getString(R.string.prompt_title, name))
            .setContentText(getString(R.string.prompt_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .addAction(0, getString(R.string.connect), connect)
            .addAction(0, getString(R.string.not_now), no)
            .build()
        nm().notify(ID_PROMPT, n)
        log("Aviso mostrado como notificación")
    }

    private fun cancelPrompt() {
        nm().cancel(ID_PROMPT)
    }

    private fun postManualNotification() {
        val pi = PendingIntent.getActivity(
            this,
            3,
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CH_PROMPT)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(getString(R.string.fail_title))
            .setContentText(getString(R.string.fail_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm().notify(ID_RESULT, n)
    }

    // ---------------------------------------------------------------- conectar

    /**
     * Android no deja a una app común forzar la conexión de audio de un dispositivo ya emparejado.
     * Lo que sí puede hacer es abrir un enlace Bluetooth con los auriculares; la mayoría los
     * engancha el audio sola al recibirlo. Si en 15 s no quedó conectado, abre Ajustes > Bluetooth.
     */
    private fun startConnect() {
        if (connecting) return
        connecting = true
        toast(getString(R.string.connecting, name))
        Thread {
            log("Intentando conectar…")
            val socket = openLink()
            log(
                if (socket != null) "Enlace abierto con los auriculares"
                else "No respondieron (¿están prendidos y en modo Bluetooth?)",
            )
            var ok = false
            for (i in 1..15) {
                if (audioConnected()) {
                    ok = true
                    break
                }
                Thread.sleep(1000)
            }
            socket?.let { closeQuietly(it) }
            connecting = false
            main.post {
                if (ok) {
                    log("Conectado ✓")
                    toast(getString(R.string.connected_toast, name))
                } else {
                    log("No quedó conectado el audio")
                    postManualNotification()
                }
            }
        }.start()
    }

    /** Intenta abrir un canal RFCOMM (manos libres) con los auriculares. Null si no responden. */
    private fun openLink(): BluetoothSocket? {
        val ad = adapter ?: return null
        return try {
            ad.cancelDiscovery()
            val socket = ad.getRemoteDevice(address).createRfcommSocketToServiceRecord(HFP_UUID)
            try {
                socket.connect()
                socket
            } catch (e: IOException) {
                closeQuietly(socket)
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun closeQuietly(s: BluetoothSocket) {
        try {
            s.close()
        } catch (e: Exception) {
            // ignorar
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text, Store.name(this)))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannels() {
        val m = nm()
        m.createNotificationChannel(
            NotificationChannel(CH_SERVICE, getString(R.string.ch_service), NotificationManager.IMPORTANCE_LOW),
        )
        m.createNotificationChannel(
            NotificationChannel(CH_PROMPT, getString(R.string.ch_prompt), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    private fun nm(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun log(msg: String) {
        EventLog.add(this, msg)
    }
}
