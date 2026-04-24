package com.example.blebeacondetector

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.UUID

class BeaconScanService : Service() {

    private var scanner: BluetoothLeScanner? = null
    private var running = false

    private var selectedUuid: String? = null
    private var selectedMajor: Int = -1
    private var selectedMinor: Int = -1

    private var toleranceMs = 10_000L
    private val scanGraceMs = 2_500L
    private var notificationsEnabled = true

    private var lastSeen: Long = 0L

    private var confirmedVisible = false
    private var candidateVisible: Boolean? = null
    private var candidateSince: Long = 0L

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForegroundNotification()
        loadSelectedBeacon()
        startBleScan()
        startVisibilityCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSelectedBeacon()
        return START_STICKY
    }

    private fun loadSelectedBeacon() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)

        selectedUuid = prefs.getString("selected_uuid", null)
        selectedMajor = prefs.getInt("selected_major", -1)
        selectedMinor = prefs.getInt("selected_minor", -1)
        toleranceMs = prefs.getLong("tolerance_ms", 10_000L)
        notificationsEnabled = prefs.getBoolean("notifications_enabled", true)

        lastSeen = 0L
        confirmedVisible = false
        candidateVisible = null
        candidateSince = 0L
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "beacon_channel")
            .setContentTitle("Monitoraggio beacon attivo")
            .setContentText("Sto cercando beacon BLE")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun startBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = bluetoothManager.adapter.bluetoothLeScanner

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        running = true
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.manufacturerSpecificData ?: return

            for (i in 0 until data.size()) {
                val manufacturerId = data.keyAt(i)
                val bytes = data.valueAt(i)

                if (manufacturerId == 0x004C && isIBeacon(bytes)) {
                    val uuid = extractUuid(bytes).toString()
                    val major = extractMajor(bytes)
                    val minor = extractMinor(bytes)

                    sendBeaconToActivity(
                        uuid = uuid,
                        major = major,
                        minor = minor,
                        rssi = result.rssi
                    )

                    if (isSelectedBeacon(uuid, major, minor)) {
                        lastSeen = System.currentTimeMillis()
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            sendEventNotification("Errore BLE", "Errore scansione: $errorCode")
        }
    }

    private fun isSelectedBeacon(uuid: String, major: Int, minor: Int): Boolean {
        val currentSelectedUuid = selectedUuid ?: return false

        return uuid == currentSelectedUuid &&
                major == selectedMajor &&
                minor == selectedMinor
    }

    private fun startVisibilityCheck() {
        Thread {
            while (running) {
                Thread.sleep(1000)

                if (selectedUuid == null) continue

                val now = System.currentTimeMillis()

                val rawVisible =
                    lastSeen > 0 && now - lastSeen <= scanGraceMs

                updateStableState(rawVisible, now)
            }
        }.start()
    }

    private fun updateStableState(rawVisible: Boolean, now: Long) {
        if (rawVisible == confirmedVisible) {
            candidateVisible = null
            candidateSince = 0L
            return
        }

        if (candidateVisible != rawVisible) {
            candidateVisible = rawVisible
            candidateSince = now
            return
        }

        if (now - candidateSince >= toleranceMs) {
            confirmedVisible = rawVisible
            candidateVisible = null
            candidateSince = 0L

            if (confirmedVisible) {

                if (notificationsEnabled) {
                    sendEventNotification(
                        "Beacon tornato visibile",
                        "Il beacon selezionato è visibile stabilmente"
                    )
                }

                sendMacroDroidIntent("BEACON_VISIBLE")

            } else {

                if (notificationsEnabled) {
                    sendEventNotification(
                        "Beacon non più visibile",
                        "Il beacon selezionato non viene rilevato stabilmente"
                    )
                }

                sendMacroDroidIntent("BEACON_LOST")
            }
        }
    }

    private fun sendMacroDroidIntent(action: String) {
        val intent = Intent(action).apply {
            putExtra("source", "BLEBeaconDetector")
            putExtra("timestamp", System.currentTimeMillis())
        }

        sendBroadcast(intent)
    }

    private fun isIBeacon(bytes: ByteArray): Boolean {
        if (bytes.size < 23) return false
        if (bytes[0] != 0x02.toByte()) return false
        if (bytes[1] != 0x15.toByte()) return false
        return true
    }

    private fun extractUuid(bytes: ByteArray): UUID {
        val uuidBytes = bytes.copyOfRange(2, 18)
        return bytesToUuid(uuidBytes)
    }

    private fun extractMajor(bytes: ByteArray): Int {
        return ((bytes[18].toInt() and 0xff) shl 8) or
                (bytes[19].toInt() and 0xff)
    }

    private fun extractMinor(bytes: ByteArray): Int {
        return ((bytes[20].toInt() and 0xff) shl 8) or
                (bytes[21].toInt() and 0xff)
    }

    private fun bytesToUuid(bytes: ByteArray): UUID {
        var msb = 0L
        var lsb = 0L

        for (i in 0..7) {
            msb = (msb shl 8) or (bytes[i].toLong() and 0xff)
        }

        for (i in 8..15) {
            lsb = (lsb shl 8) or (bytes[i].toLong() and 0xff)
        }

        return UUID(msb, lsb)
    }

    private fun sendBeaconToActivity(
        uuid: String,
        major: Int,
        minor: Int,
        rssi: Int
    ) {
        val intent = Intent("BEACON_FOUND").apply {
            setPackage(packageName)
            putExtra("uuid", uuid)
            putExtra("major", major)
            putExtra("minor", minor)
            putExtra("rssi", rssi)
            putExtra("lastSeen", System.currentTimeMillis())
        }

        sendBroadcast(intent)
    }

    private fun sendEventNotification(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, "beacon_channel")
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "beacon_channel",
            "Beacon Monitor",
            NotificationManager.IMPORTANCE_DEFAULT
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        running = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        ) {
            scanner?.stopScan(scanCallback)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}