package com.example.blebeacondetector

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blebeacondetector.ui.theme.BLEBeaconDetectorTheme
import java.text.SimpleDateFormat
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.statusBarsPadding
import java.util.*

data class BeaconInfo(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val lastSeen: Long,
    val visible: Boolean
) {
    val key: String
        get() = "$uuid|$major|$minor"
}

class MainActivity : ComponentActivity() {

    private val beacons = mutableStateListOf<BeaconInfo>()
    private var selectedBeaconKey by mutableStateOf<String?>(null)
    private var toleranceSeconds by mutableStateOf("10")
    private var notificationsEnabled by mutableStateOf(true)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestBackgroundLocationPermission()
            loadSelectedBeacon()
            startBeaconService()
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startBeaconService()
        }

    private val beaconReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val uuid = intent?.getStringExtra("uuid") ?: return
            val major = intent.getIntExtra("major", -1)
            val minor = intent.getIntExtra("minor", -1)
            val rssi = intent.getIntExtra("rssi", -999)
            val lastSeen = intent.getLongExtra("lastSeen", System.currentTimeMillis())

            val visible = intent.getBooleanExtra("visible", false)

            val beacon = BeaconInfo(uuid, major, minor, rssi, lastSeen, visible)
            val index = beacons.indexOfFirst { it.key == beacon.key }

            if (index >= 0) {
                beacons[index] = beacon
            } else {
                beacons.add(beacon)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiverCompat()
        loadSelectedBeacon()
        loadTolerance()
        loadNotificationSetting()
        requestPermissions()
        requestBatteryOptimizationExemption()

        setContent {
            BLEBeaconDetectorTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("BLE Beacon Scanner", style = MaterialTheme.typography.headlineSmall)

                    Text("Beacon trovati: ${beacons.size}")

                    Text(
                        text = if (selectedBeaconKey == null)
                            "Beacon selezionato: nessuno"
                        else
                            "Beacon selezionato: $selectedBeaconKey",
                        style = MaterialTheme.typography.bodySmall
                    )

                    OutlinedTextField(
                        value = toleranceSeconds,
                        onValueChange = { value ->
                            toleranceSeconds = value.filter { it.isDigit() }

                            val seconds = toleranceSeconds.toLongOrNull() ?: 10L
                            val millis = seconds.coerceAtLeast(1L) * 1000L

                            getSharedPreferences("beacon_prefs", MODE_PRIVATE)
                                .edit()
                                .putLong("tolerance_ms", millis)
                                .apply()

                            startBeaconService()
                        },
                        label = { Text("Tolleranza (secondi)", color = Color.Black) },
                        textStyle = LocalTextStyle.current.copy(color = Color.Black),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            focusedLabelColor = Color.Black,
                            unfocusedLabelColor = Color.DarkGray,
                            focusedBorderColor = Color.Black,
                            unfocusedBorderColor = Color.DarkGray,
                            cursorColor = Color.Black
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Notifiche abilitate",
                            color = Color.Black
                        )

                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { enabled ->
                                notificationsEnabled = enabled

                                getSharedPreferences("beacon_prefs", MODE_PRIVATE)
                                    .edit()
                                    .putBoolean("notifications_enabled", enabled)
                                    .apply()

                                startBeaconService()
                            }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // Controllo servizio
                        Text("Monitoraggio", style = MaterialTheme.typography.labelMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { startBeaconService() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Avvia")
                            }

                            Button(
                                onClick = { stopBeaconService() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Ferma")
                            }
                        }

                        // Gestione beacon
                        Text("Gestione beacon", style = MaterialTheme.typography.labelMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { clearSelectedBeacon() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Deseleziona")
                            }

                            Button(
                                onClick = { startDiscoveryScan() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Scansiona")
                            }
                        }
                    }

                    Text(
                        text = "Lista beacons",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(beacons.sortedByDescending { it.lastSeen }) { beacon ->
                            BeaconCard(
                                beacon = beacon,
                                isSelected = beacon.key == selectedBeaconKey,
                                onSelect = { saveSelectedBeacon(beacon) }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun saveSelectedBeacon(beacon: BeaconInfo) {
        selectedBeaconKey = beacon.key

        getSharedPreferences("beacon_prefs", MODE_PRIVATE)
            .edit()
            .putString("selected_uuid", beacon.uuid)
            .putInt("selected_major", beacon.major)
            .putInt("selected_minor", beacon.minor)
            .apply()

        startBeaconService()
    }

    private fun startDiscoveryScan() {
        val intent = Intent(this, BeaconScanService::class.java).apply {
            action = "DISCOVERY_SCAN"
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun loadTolerance() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)
        val millis = prefs.getLong("tolerance_ms", 10_000L)
        toleranceSeconds = (millis / 1000L).toString()
    }

    private fun loadNotificationSetting() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)
        notificationsEnabled = prefs.getBoolean("notifications_enabled", true)
    }

    private fun clearSelectedBeacon() {
        selectedBeaconKey = null

        getSharedPreferences("beacon_prefs", MODE_PRIVATE)
            .edit()
            .remove("selected_uuid")
            .remove("selected_major")
            .remove("selected_minor")
            .apply()

        startBeaconService()
    }

    private fun loadSelectedBeacon() {
        val prefs = getSharedPreferences("beacon_prefs", MODE_PRIVATE)
        val uuid = prefs.getString("selected_uuid", null)
        val major = prefs.getInt("selected_major", -1)
        val minor = prefs.getInt("selected_minor", -1)

        selectedBeaconKey = if (uuid != null && major >= 0 && minor >= 0) {
            "$uuid|$major|$minor"
        } else {
            null
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        permissions += Manifest.permission.ACCESS_FINE_LOCATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fineGranted =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

            val backgroundGranted =
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (fineGranted && !backgroundGranted) {
                backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager

        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun startBeaconService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, BeaconScanService::class.java)
        )
    }

    private fun stopBeaconService() {
        val intent = Intent(this, BeaconScanService::class.java).apply {
            action = "STOP_SERVICE"
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun registerReceiverCompat() {
        val filter = IntentFilter("BEACON_FOUND")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(beaconReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(beaconReceiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(beaconReceiver)
        super.onDestroy()
    }
}

@Composable
fun BeaconCard(
    beacon: BeaconInfo,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("UUID: ${beacon.uuid}", style = MaterialTheme.typography.bodySmall)
            Text("Major: ${beacon.major} | Minor: ${beacon.minor}")
            Text("RSSI: ${beacon.rssi} dBm")
            Text("Ultimo: ${formatter.format(Date(beacon.lastSeen))}")
            Text("Stato: ${if (beacon.visible) "VISIBLE" else "NOT VISIBLE"}")

            if (isSelected) {
                Text("SELEZIONATO")
            } else {
                Button(onClick = onSelect) {
                    Text("Seleziona questo beacon")
                }
            }
        }
    }
}