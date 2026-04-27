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
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.bluetooth.le.ScanFilter

data class StoredBeacon(
    val uuid: String,
    val major: Int,
    val minor: Int,
    var rssi: Int,
    var lastSeen: Long,
    var visible: Boolean,
    var visibleCandidateSince: Long = 0L
) {
    val key: String
        get() = "$uuid|$major|$minor"
}

class BeaconScanService : Service() {

    private var scanner: BluetoothLeScanner? = null
    private var running = false

    private val beacons = mutableMapOf<String, StoredBeacon>()

    private var selectedUuid: String? = null
    private var selectedMajor: Int = -1
    private var selectedMinor: Int = -1

    private var enterToleranceMs = 10_000L
    private var exitToleranceMs = 30_000L
    private var notificationsEnabled = true

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRestart = 0L
    private var lastAnyScanSeen = 0L
    private var discoveryMode = false
    private var discoveryUntil = 0L
    private var visibilityThreadStarted = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForegroundNotification()

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BLEBeaconDetector::WakeLock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
        } catch (_: Exception) {
        }

        running = true

        loadSettings()
        loadStoredBeacons()
        broadcastAllBeacons()

        startBleScan()
        startVisibilityCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        loadStoredBeacons()
        broadcastAllBeacons()

        if (intent?.action == "STOP_SERVICE") {
            logToFile("STOP_SERVICE_REQUEST")

            running = false

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    scanner?.stopScan(scanCallback)
                    logToFile("SCAN_STOPPED")
                } catch (_: Exception) {
                }
            }

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    logToFile("WAKELOCK_RELEASED")
                }
            } catch (_: Exception) {
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            return START_NOT_STICKY
        }

        if (intent?.action == "DISCOVERY_SCAN") {
            discoveryMode = true
            discoveryUntil = System.currentTimeMillis() + 15_000L
            logToFile("DISCOVERY_REQUEST | durationMs=15000")
        }

        restartScan()

        return START_STICKY
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)

        selectedUuid = prefs.getString("selected_uuid", null)
        selectedMajor = prefs.getInt("selected_major", -1)
        selectedMinor = prefs.getInt("selected_minor", -1)
        enterToleranceMs = prefs.getLong("enter_tolerance_ms", 10_000L)
        exitToleranceMs = prefs.getLong("exit_tolerance_ms", 30_000L)
        notificationsEnabled = prefs.getBoolean("notifications_enabled", true)
    }

    private fun loadStoredBeacons() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)
        val json = prefs.getString("known_beacons", "[]") ?: "[]"

        beacons.clear()

        val array = JSONArray(json)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)

            val beacon = StoredBeacon(
                uuid = obj.getString("uuid"),
                major = obj.getInt("major"),
                minor = obj.getInt("minor"),
                rssi = obj.optInt("rssi", -999),
                lastSeen = obj.getLong("lastSeen"),
                visible = obj.getBoolean("visible"),
                visibleCandidateSince = obj.optLong("visibleCandidateSince", 0L)
            )

            beacons[beacon.key] = beacon
        }
    }

    private fun saveStoredBeacons() {
        val array = JSONArray()

        beacons.values.forEach { beacon ->
            val obj = JSONObject()
                .put("uuid", beacon.uuid)
                .put("major", beacon.major)
                .put("minor", beacon.minor)
                .put("rssi", beacon.rssi)
                .put("lastSeen", beacon.lastSeen)
                .put("visible", beacon.visible)
                .put("visibleCandidateSince", beacon.visibleCandidateSince)

            array.put(obj)
        }

        getSharedPreferences("beacon_prefs", MODE_PRIVATE)
            .edit()
            .putString("known_beacons", array.toString())
            .apply()
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val filters = if (discoveryMode) {
            null
        } else {
            buildSelectedBeaconFilter()
        }

        if (!discoveryMode && filters == null) {
            logToFile("SCAN_NOT_STARTED | reason=no_selected_beacon")
            return
        }

        logToFile(
            "SCAN_PRE_START | discoveryMode=$discoveryMode | filters=${filters?.size ?: 0} | selectedUuid=$selectedUuid | selectedMajor=$selectedMajor | selectedMinor=$selectedMinor"
        )

        scanner?.startScan(filters, settings, scanCallback)

        logToFile(
            if (discoveryMode)
                "SCAN_START | mode=DISCOVERY_UNFILTERED"
            else
                "SCAN_START | mode=FILTERED_SELECTED_BEACON"
        )
    }

    private fun buildSelectedBeaconFilter(): List<ScanFilter>? {
        val uuid = selectedUuid ?: return null
        if (selectedMajor < 0 || selectedMinor < 0) return null

        val uuidObj = UUID.fromString(uuid)

        val manufacturerData = ByteArray(23)
        val manufacturerMask = ByteArray(23)

        manufacturerData[0] = 0x02
        manufacturerData[1] = 0x15

        manufacturerMask[0] = 0xFF.toByte()
        manufacturerMask[1] = 0xFF.toByte()

        uuidToBytes(uuidObj).copyInto(manufacturerData, 2)

        manufacturerData[18] = ((selectedMajor shr 8) and 0xFF).toByte()
        manufacturerData[19] = (selectedMajor and 0xFF).toByte()
        manufacturerData[20] = ((selectedMinor shr 8) and 0xFF).toByte()
        manufacturerData[21] = (selectedMinor and 0xFF).toByte()

        for (i in 2..21) {
            manufacturerMask[i] = 0xFF.toByte()
        }

        manufacturerMask[22] = 0x00

        val filter = ScanFilter.Builder()
            .setManufacturerData(0x004C, manufacturerData, manufacturerMask)
            .build()

        return listOf(filter)
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val bytes = ByteArray(16)

        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits

        for (i in 7 downTo 0) {
            bytes[i] = (msb and 0xFF).toByte()
            msb = msb shr 8
        }

        for (i in 15 downTo 8) {
            bytes[i] = (lsb and 0xFF).toByte()
            lsb = lsb shr 8
        }

        return bytes
    }

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.manufacturerSpecificData ?: return
            val now = System.currentTimeMillis()

            if (discoveryMode && now > discoveryUntil) {
                discoveryMode = false
                restartScan()
            }

            for (i in 0 until data.size()) {
                val manufacturerId = data.keyAt(i)
                val bytes = data.valueAt(i)

                if (manufacturerId == 0x004C && isIBeacon(bytes)) {
                    val uuid = extractUuid(bytes).toString()
                    val major = extractMajor(bytes)
                    val minor = extractMinor(bytes)
                    val key = "$uuid|$major|$minor"

                    val beacon = beacons[key] ?: StoredBeacon(
                        uuid = uuid,
                        major = major,
                        minor = minor,
                        rssi = result.rssi,
                        lastSeen = now,
                        visible = false,
                        visibleCandidateSince = 0L
                    )

                    beacon.rssi = result.rssi
                    beacon.lastSeen = now

                    logToFile(
                        "DETECTED | id=${beacon.key} | uuid=${beacon.uuid} | major=${beacon.major} | minor=${beacon.minor} | rssi=${beacon.rssi} | lastSeen=${beacon.lastSeen} | visible=${beacon.visible} | visibleCandidateSince=${beacon.visibleCandidateSince}"
                    )

                    beacons[key] = beacon
                    lastAnyScanSeen = now

                    sendBeaconToActivity(beacon)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            sendEventNotification("Errore BLE", "Errore scansione: $errorCode")
        }
    }

    private fun startVisibilityCheck() {
        if (visibilityThreadStarted) return
        visibilityThreadStarted = true

        Thread {
            while (running) {
                Thread.sleep(1000)

                val now = System.currentTimeMillis()
                var changed = false

                if (discoveryMode && now > discoveryUntil) {
                    discoveryMode = false
                    restartScan()
                }

                beacons.values.forEach { beacon ->
                    val delta = now - beacon.lastSeen
                    val recentlySeen = delta <= 2_500L

                    logToFile(
                        "EVAL | id=${beacon.key} | " +
                                "visible=${beacon.visible} | " +
                                "now=$now | " +
                                "lastSeen=${beacon.lastSeen} | " +
                                "deltaMs=$delta | " +
                                "recentlySeen=$recentlySeen | " +
                                "enterToleranceMs=\$enterToleranceMs | exitToleranceMs=\$exitToleranceMs  | " +
                                "candidateSince=${beacon.visibleCandidateSince} | " +
                                "candidateAgeMs=${if (beacon.visibleCandidateSince > 0) now - beacon.visibleCandidateSince else 0}"
                    )

                    if (beacon.visible) {
                        beacon.visibleCandidateSince = 0L

                        if (delta > exitToleranceMs) {
                            beacon.visible = false

                            logToFile(
                                "STATE_CHANGE | id=${beacon.key} | newState=NOT_VISIBLE | deltaMs=$delta | enterToleranceMs=\$enterToleranceMs | exitToleranceMs=\$exitToleranceMs | rssi=${beacon.rssi} | lastSeen=${beacon.lastSeen} | visibleCandidateSince=${beacon.visibleCandidateSince}"
                            )

                            changed = true
                            sendBeaconToActivity(beacon)

                            if (isSelectedBeacon(beacon)) {
                                if (notificationsEnabled) {
                                    sendEventNotification(
                                        "Beacon non più visibile",
                                        "Il beacon selezionato non viene rilevato"
                                    )
                                }

                                sendMacroDroidIntent("BEACON_LOST")
                            }
                        }
                    } else {
                        if (recentlySeen) {
                            if (beacon.visibleCandidateSince == 0L) {
                                beacon.visibleCandidateSince = now
                                changed = true
                            }

                            if (now - beacon.visibleCandidateSince >= enterToleranceMs) {
                                beacon.visible = true

                                logToFile(
                                    "STATE_CHANGE | id=${beacon.key} | newState=VISIBLE | candidateDurationMs=${now - beacon.visibleCandidateSince} | enterToleranceMs=\$enterToleranceMs | exitToleranceMs=\$exitToleranceMs | rssi=${beacon.rssi} | lastSeen=${beacon.lastSeen} | visibleCandidateSince=${beacon.visibleCandidateSince}"
                                )

                                beacon.visibleCandidateSince = 0L
                                changed = true
                                sendBeaconToActivity(beacon)

                                if (isSelectedBeacon(beacon)) {
                                    if (notificationsEnabled) {
                                        sendEventNotification(
                                            "Beacon tornato visibile",
                                            "Il beacon selezionato è di nuovo visibile"
                                        )
                                    }

                                    sendMacroDroidIntent("BEACON_VISIBLE")
                                }
                            }
                        } else {
                            if (beacon.visibleCandidateSince != 0L) {
                                beacon.visibleCandidateSince = 0L
                                changed = true
                            }
                        }
                    }
                }

                if (changed) {
                    saveStoredBeacons()
                }

                if (!discoveryMode && now - lastAnyScanSeen > 30_000 && now - lastRestart > 30_000) {
                    lastRestart = now
                    restartScan()
                }
            }
        }.start()
    }

    private fun restartScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }

        logToFile("SCAN_RESTART")
        startBleScan()
    }

    private fun isSelectedBeacon(beacon: StoredBeacon): Boolean {
        val currentSelectedUuid = selectedUuid ?: return false

        return beacon.uuid == currentSelectedUuid &&
                beacon.major == selectedMajor &&
                beacon.minor == selectedMinor
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

    private fun sendBeaconToActivity(beacon: StoredBeacon) {
        val intent = Intent("BEACON_FOUND").apply {
            setPackage(packageName)
            putExtra("uuid", beacon.uuid)
            putExtra("major", beacon.major)
            putExtra("minor", beacon.minor)
            putExtra("rssi", beacon.rssi)
            putExtra("lastSeen", beacon.lastSeen)
            putExtra("visible", beacon.visible)
        }

        sendBroadcast(intent)
    }

    private fun broadcastAllBeacons() {
        beacons.values.forEach { sendBeaconToActivity(it) }
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
        visibilityThreadStarted = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: Exception) {
            }
        }

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }

        saveStoredBeacons()

        super.onDestroy()
    }

    private fun logToFile(message: String) {
//        try {
//            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
//            val timestamp = formatter.format(Date())
//
//            val file = File(filesDir, "beacon_log.txt")
//
//            FileWriter(file, true).use { writer ->
//                writer.append("$timestamp | $message\n")
//            }
//        } catch (_: Exception) {
//        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}