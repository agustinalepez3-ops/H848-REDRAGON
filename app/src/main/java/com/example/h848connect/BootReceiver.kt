package com.example.h848connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Vuelve a arrancar la detección cuando se reinicia el celular o se actualiza la app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.enabled(context) && Store.address(context) != null && Perms.hasBluetooth(context)) {
            try {
                DetectorService.start(context)
            } catch (e: Exception) {
                EventLog.add(context, "No pude arrancar solo: ${e.message}")
            }
        }
    }
}
