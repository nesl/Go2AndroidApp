package com.example.boxclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings


class BeaconScanner(
    context: Context,
    private val onNearbyBoxesChanged: (Set<Int>) -> Unit
) {
    private val appContext = context.applicationContext

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private val leScanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private var bleScanning = false


    // CONFIG: how often to restart discovery, and how long a node stays "nearby"
    private val SCAN_INTERVAL_MS = 5_000L    // every 10s restart discovery
    private val STALE_TIMEOUT_MS = 30_000L    // if not seen for 15s → not nearby

    // Currently "nearby" boxes + last seen timestamps
    private val nearbyBoxIds = mutableSetOf<Int>()
    private val lastSeenMillis = mutableMapOf<Int, Long>()

    private var receiverRegistered = false
    private val handler = Handler(Looper.getMainLooper())

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun safeDeviceName(device: BluetoothDevice): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                null
            } else {
                device.name
            }
        } catch (se: SecurityException) {
            Log.w("BeaconScanner", "Missing permission for device name", se)
            null
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val rssi = result.rssi
            val record = result.scanRecord
            val advName = record?.deviceName
            val devName = result.device.name
            val addr = result.device.address

            val name = advName ?: devName
            Log.d("BeaconScanner", "BLE found: name=$name addr=$addr rssi=$rssi")

            // Expect names like "CNode1##"
            if (name != null && name.startsWith("CNode1")) {
                val suffix = name.removePrefix("CNode1")
                val boxId = suffix.toIntOrNull()
                if (boxId != null && rssi > -90) {
                    markBoxSeen(boxId)
                }
            }

            // If name is null, your nodes may be manufacturer-data beacons.
            // In that case, log manufacturer data once to see what's inside:
            // val msd = record?.manufacturerSpecificData
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("BeaconScanner", "BLE scan failed: $errorCode")
        }
    }


    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            if (BluetoothDevice.ACTION_FOUND != intent.action) return

            val device: BluetoothDevice? =
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val rssi: Int =
                intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

            if (device == null) return

            val name = safeDeviceName(device)
            val addr = device.address
            Log.d("BeaconScanner", "Found device: name=$name addr=$addr rssi=$rssi")

            // Expect names like "CNode1##"
            if (name != null && name.startsWith("CNode1")) {
                val suffix = name.removePrefix("CNode1")
                val boxId = suffix.toIntOrNull()
                if (boxId != null && rssi > -90) { // tweak RSSI threshold as needed
                    markBoxSeen(boxId)
                }
            }
        }
    }

    /**
     * Called whenever we see a box's beacon.
     * Updates last-seen time and recomputes the "nearby" set,
     * expiring any boxes not seen in STALE_TIMEOUT_MS.
     */
    private fun markBoxSeen(boxId: Int) {
        val now = System.currentTimeMillis()
        lastSeenMillis[boxId] = now

        val activeIds = lastSeenMillis
            .filter { (_, ts) -> now - ts <= STALE_TIMEOUT_MS }
            .keys

        nearbyBoxIds.clear()
        nearbyBoxIds.addAll(activeIds)

        onNearbyBoxesChanged(nearbyBoxIds.toSet())
    }

    /**
     * Periodically restart discovery every SCAN_INTERVAL_MS.
     * This helps keep discovery fresh and makes sure we keep seeing devices.
     */

    private val rescanRunnable = object : Runnable {
        override fun run() {
            val bt = adapter
            if (bt == null || !bt.isEnabled || !hasRequiredPermissions()) {
                Log.w("BeaconScanner", "Cannot rescan: BT or permissions missing")
            } else {
                try {
                    if (bt.isDiscovering) {
                        bt.cancelDiscovery()
                    }
                    bt.startDiscovery()
                    Log.d("BeaconScanner", "Classic discovery (re)started")
                } catch (se: SecurityException) {
                    Log.e("BeaconScanner", "SecurityException restarting discovery", se)
                }
            }

            // Also expire stale nodes in case we haven't seen them recently
            expireStaleNodes()

            // Schedule next rescan
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }

/*
    private val rescanRunnable = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            val bt = adapter
            val scanner = leScanner

            if (bt == null || !bt.isEnabled || !hasRequiredPermissions() || scanner == null) {
                Log.w("BeaconScanner", "Cannot rescan: BT/scanner/permissions missing")
            } else {
                try {
                    // Restart scan to keep things fresh
                    if (bleScanning) scanner.stopScan(bleCallback)
                    val settings = ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build()
                    scanner.startScan(null, settings, bleCallback)
                    bleScanning = true
                    Log.d("BeaconScanner", "BLE scan (re)started")
                } catch (se: SecurityException) {
                    Log.e("BeaconScanner", "SecurityException restarting BLE scan", se)
                }
            }

            expireStaleNodes()
            handler.postDelayed(this, SCAN_INTERVAL_MS)
        }
    }
*/
    private fun expireStaleNodes() {
        val now = System.currentTimeMillis()
        val activeIds = lastSeenMillis
            .filter { (_, ts) -> now - ts <= STALE_TIMEOUT_MS }
            .keys

        if (activeIds.size != nearbyBoxIds.size || !nearbyBoxIds.containsAll(activeIds)) {
            nearbyBoxIds.clear()
            nearbyBoxIds.addAll(activeIds)
            onNearbyBoxesChanged(nearbyBoxIds.toSet())
        }

        // Drop stale entries from lastSeenMillis map as well
        val staleKeys = lastSeenMillis
            .filter { (_, ts) -> now - ts > STALE_TIMEOUT_MS }
            .keys
            .toList()
        staleKeys.forEach { lastSeenMillis.remove(it) }
    }


    fun startScanning() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            Log.w("BeaconScanner", "Bluetooth adapter not available or not enabled")
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w("BeaconScanner", "Missing Bluetooth permissions; not starting discovery")
            return
        }

        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            appContext.registerReceiver(receiver, filter)
            receiverRegistered = true
        }

        try {
            if (bt.isDiscovering) {
                bt.cancelDiscovery()
            }
            bt.startDiscovery()
            Log.d("BeaconScanner", "Classic discovery started")
        } catch (se: SecurityException) {
            Log.e("BeaconScanner", "SecurityException starting discovery", se)
        }

        // Kick off periodic rescans + expiry
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, SCAN_INTERVAL_MS)
    }

/*
    @SuppressLint("MissingPermission")
    fun startScanning() {
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            Log.w("BeaconScanner", "Bluetooth adapter not available or not enabled")
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w("BeaconScanner", "Missing Bluetooth permissions; not starting scan")
            return
        }

        val scanner = leScanner
        if (scanner == null) {
            Log.w("BeaconScanner", "BLE scanner unavailable")
            return
        }

        // Start BLE scanning
        try {
            if (bleScanning) {
                scanner.stopScan(bleCallback)
                bleScanning = false
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            scanner.startScan(null, settings, bleCallback)
            bleScanning = true
            Log.d("BeaconScanner", "BLE scan started")
        } catch (se: SecurityException) {
            Log.e("BeaconScanner", "SecurityException starting BLE scan", se)
        }

        // Kick off periodic rescans + expiry
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, SCAN_INTERVAL_MS)
    }
    */
    fun stopScanning() {
        val bt = adapter
        try {
            if (bt != null && bt.isDiscovering) {
                bt.cancelDiscovery()
            }
        } catch (se: SecurityException) {
            Log.e("BeaconScanner", "SecurityException cancelling discovery", se)
        }

        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
            receiverRegistered = false
        }

        handler.removeCallbacks(rescanRunnable)

        nearbyBoxIds.clear()
        lastSeenMillis.clear()
        onNearbyBoxesChanged(emptySet())
    }
}

/*
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        val scanner = leScanner
        try {
            if (scanner != null && bleScanning) {
                scanner.stopScan(bleCallback)
                bleScanning = false
            }
        } catch (se: SecurityException) {
            Log.e("BeaconScanner", "SecurityException stopping BLE scan", se)
        }

        handler.removeCallbacks(rescanRunnable)

        nearbyBoxIds.clear()
        lastSeenMillis.clear()
        onNearbyBoxesChanged(emptySet())
    }
}
*/

