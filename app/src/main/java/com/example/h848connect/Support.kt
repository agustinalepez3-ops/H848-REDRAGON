package com.example.h848connect

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Preferencias guardadas: qué auriculares vigilar y si la detección está activa. */
object Store {
    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("h848", Context.MODE_PRIVATE)

    fun address(ctx: Context): String? = prefs(ctx).getString("address", null)

    fun name(ctx: Context): String = prefs(ctx).getString("name", null) ?: "tus auriculares"

    fun setDevice(ctx: Context, address: String, name: String) {
        prefs(ctx).edit().putString("address", address).putString("name", name).apply()
    }

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", value).apply()
    }

    fun probe(ctx: Context): Boolean = prefs(ctx).getBoolean("probe", false)

    fun setProbe(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("probe", value).apply()
    }
}

/** Registro corto de eventos, se muestra en la pantalla principal para poder ajustar la detección. */
object EventLog {
    private const val KEY = "log"
    private const val MAX_LINES = 60
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(ctx: Context, msg: String) {
        val sp = Store.prefs(ctx)
        val lines = (sp.getString(KEY, "") ?: "").split("\n").filter { it.isNotBlank() }.toMutableList()
        lines.add("${fmt.format(Date())}  $msg")
        sp.edit().putString(KEY, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
    }

    fun read(ctx: Context): String = Store.prefs(ctx).getString(KEY, "") ?: ""

    @Synchronized
    fun clear(ctx: Context) {
        Store.prefs(ctx).edit().remove(KEY).apply()
    }
}

object Perms {
    fun required(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    fun hasBluetooth(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.BLUETOOTH_CONNECT) &&
            granted(ctx, Manifest.permission.BLUETOOTH_SCAN)

    private fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
