package com.example.blebeacondetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val serviceIntent = Intent(context, BeaconScanService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}