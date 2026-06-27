package io.github.ssayala.ledticker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.ssayala.ledticker.data.KnownDeviceStore
import io.github.ssayala.ledticker.model.KnownDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android BLE wrapper, the counterpart of the iOS BLEManager. One active
 * connection; surfaces knownDevices, activeDevice and inRange (seen in the
 * last ~10 s). Identity is the BLE MAC address.
 *
 * Auth: leads with system bonding (the ESP32 requests passkey pairing on
 * connect, so Android shows its PIN dialog). If bonding is declined or a
 * write is rejected for lack of auth, [authRequired] fires so the UI can
 * collect the PIN and [submitPin] writes it to the Auth characteristic.
 */
@SuppressLint("MissingPermission")
class BleManager(
    private val appContext: Context,
    val simulated: Boolean = isEmulator(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val store = KnownDeviceStore(appContext)

    // MARK: - Published state

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _activeDevice = MutableStateFlow<KnownDevice?>(null)
    val activeDevice: StateFlow<KnownDevice?> = _activeDevice.asStateFlow()

    private val _knownDevices = MutableStateFlow<List<KnownDevice>>(emptyList())
    val knownDevices: StateFlow<List<KnownDevice>> = _knownDevices.asStateFlow()

    private val _inRange = MutableStateFlow<Set<String>>(emptySet())
    val inRange: StateFlow<Set<String>> = _inRange.asStateFlow()

    private val _advertisedNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val advertisedNames: StateFlow<Map<String, String>> = _advertisedNames.asStateFlow()

    private val _connectingTo = MutableStateFlow<String?>(null)
    val connectingTo: StateFlow<String?> = _connectingTo.asStateFlow()

    /** True once the link is bonded or the PIN has been accepted. */
    private val _authed = MutableStateFlow(false)
    val authed: StateFlow<Boolean> = _authed.asStateFlow()

    private val _authRequired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when a write needs the PIN and bonding isn't in progress. */
    val authRequired: SharedFlow<Unit> = _authRequired.asSharedFlow()

    private val _pinRejected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Fires when a submitted PIN was rejected by the device. */
    val pinRejected: SharedFlow<Unit> = _pinRejected.asSharedFlow()

    // MARK: - Internal connection state

    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<CharKind, BluetoothGattCharacteristic>()
    private var ambientScanRequested = false
    private var scanActive = false
    private var pendingNextConnect: KnownDevice? = null
    private var connectTimeoutJob: Job? = null

    private val lastSeen = mutableMapOf<String, Long>()
    private var pruneJob: Job? = null

    // Declared before init{}: setupSimulatedDevices() (called from init)
    // writes into it, and Kotlin runs property initializers in source order.
    private val simStores = mutableMapOf<String, MutableMap<CharKind, ByteArray>>()

    init {
        if (!simulated) {
            _knownDevices.value = store.load()
            registerReceivers()
            refreshAdapterState()
        } else {
            setupSimulatedDevices()
        }
    }

    // MARK: - Adapter / Bluetooth state

    private fun refreshAdapterState() {
        if (!hasConnectPermission()) {
            _state.value = ConnectionState.Unauthorized
            return
        }
        _state.value = when {
            adapter == null -> ConnectionState.Failed("Bluetooth LE unsupported")
            !adapter.isEnabled -> ConnectionState.PoweredOff
            else -> ConnectionState.Idle
        }
        reconcileScan()
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        appContext.registerReceiver(receiver, filter)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                when (intent.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> refreshAdapterState()
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bonded = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                        )
                        val dev: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        if (dev?.address == gatt?.device?.address &&
                            bonded == BluetoothDevice.BOND_BONDED
                        ) {
                            // Bonding succeeded — the link is authenticated.
                            _authed.value = true
                            pump()
                        }
                    }
                }
            }
        }
    }

    // MARK: - Scan

    fun startAmbientScan() {
        if (simulated) return
        ambientScanRequested = true
        reconcileScan()
    }

    fun stopAmbientScan() {
        if (simulated) return
        ambientScanRequested = false
        reconcileScan()
    }

    fun restartAmbientScan() {
        if (simulated || !ambientScanRequested) return
        if (scanActive) {
            runCatching { scanner?.stopScan(scanCallback) }
            scanActive = false
        }
        reconcileScan()
    }

    private val isConnectionInFlight: Boolean
        get() = _state.value is ConnectionState.Connecting ||
            _state.value is ConnectionState.Discovering

    private fun reconcileScan() {
        val a = adapter ?: return
        if (!a.isEnabled || !hasScanPermission()) return
        val wantScan = !isConnectionInFlight &&
            (ambientScanRequested || _connectingTo.value != null)
        if (wantScan && !scanActive) {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(CharKind.SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching { scanner?.startScan(listOf(filter), settings, scanCallback) }
            scanActive = true
            if (_state.value is ConnectionState.Idle || _state.value is ConnectionState.Failed) {
                _state.value = ConnectionState.Scanning
            }
        } else if (!wantScan && scanActive) {
            runCatching { scanner?.stopScan(scanCallback) }
            scanActive = false
            if (_state.value is ConnectionState.Scanning) _state.value = ConnectionState.Idle
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
            scope.launch {
                markInRange(result.device.address, name)
                refineKnownFromDiscovery(result.device.address, name)
                // Slow-path connect: if we're scanning for this address, attach.
                if (_connectingTo.value == result.device.address) {
                    cancelConnectTimeout()
                    attachAndConnect(result.device)
                }
            }
        }
    }

    private fun markInRange(address: String, name: String?) {
        lastSeen[address] = System.currentTimeMillis()
        if (address !in _inRange.value) {
            _inRange.value = _inRange.value + address
        }
        if (!name.isNullOrEmpty() && _advertisedNames.value[address] != name) {
            _advertisedNames.value = _advertisedNames.value + (address to name)
        }
        startPruneIfNeeded()
    }

    private fun startPruneIfNeeded() {
        if (pruneJob != null || lastSeen.isEmpty()) return
        pruneJob = scope.launch {
            while (lastSeen.isNotEmpty()) {
                delay(PRUNE_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - IN_RANGE_TTL_MS
                val stale = lastSeen.filterValues { it < cutoff }.keys.toList()
                if (stale.isNotEmpty()) {
                    stale.forEach { lastSeen.remove(it) }
                    _inRange.value = _inRange.value - stale.toSet()
                    _advertisedNames.value = _advertisedNames.value - stale.toSet()
                }
            }
            pruneJob = null
        }
    }

    // MARK: - Connect / Disconnect

    fun connect(device: KnownDevice) {
        if (simulated) {
            simConnect(device); return
        }
        val a = adapter ?: return
        val current = gatt
        if (current != null && current.device.address != device.id) {
            pendingNextConnect = device
            disconnectInternal()
            return
        }
        if (_activeDevice.value?.id == device.id && _state.value is ConnectionState.Ready) return
        cancelConnectTimeout()

        val remote = runCatching { a.getRemoteDevice(device.id) }.getOrNull()
        if (remote != null && device.id in _inRange.value) {
            attachAndConnect(remote); return
        }
        // Even if not currently in range, try a direct connect; also arm a
        // scan-and-match fallback with a timeout.
        _connectingTo.value = device.id
        if (remote != null) {
            attachAndConnect(remote)
        } else {
            reconcileScan()
        }
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (_connectingTo.value == device.id &&
                _state.value !is ConnectionState.Ready
            ) {
                cancelConnectTimeout()
                disconnectInternal()
                _state.value = ConnectionState.Failed("Device not found")
                reconcileScan()
            }
        }
    }

    /** Connect to a not-yet-enrolled address discovered in a scan. */
    fun connectToAddress(address: String, advertisedName: String) {
        if (simulated) return
        val synthetic = KnownDevice(
            id = address,
            friendlyName = advertisedName,
            advertisedName = advertisedName,
            lastConnectedMillis = System.currentTimeMillis(),
        )
        connect(synthetic)
    }

    fun disconnect() {
        if (simulated) {
            simDisconnect(); return
        }
        pendingNextConnect = null
        disconnectInternal()
    }

    private fun disconnectInternal() {
        cancelConnectTimeout()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        // onConnectionStateChange may not fire after an explicit close, so
        // clear here and run any queued switch.
        gatt = null
        characteristics.clear()
        _authed.value = false
        opInFlight = false
        opQueue.clear()
    }

    private fun attachAndConnect(device: BluetoothDevice) {
        characteristics.clear()
        _authed.value = false
        // Provisional active device so the UI can show the in-flight connect.
        val existing = _knownDevices.value.firstOrNull { it.id == device.address }
        _activeDevice.value = existing ?: KnownDevice(
            id = device.address,
            friendlyName = device.name?.takeIf { it.isNotEmpty() } ?: KnownDevice.PLACEHOLDER_NAME,
            advertisedName = device.name?.takeIf { it.isNotEmpty() } ?: KnownDevice.PLACEHOLDER_NAME,
            lastConnectedMillis = System.currentTimeMillis(),
        )
        _state.value = ConnectionState.Connecting
        reconcileScan()
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        _connectingTo.value = null
    }

    // MARK: - Known list mutations

    fun forget(device: KnownDevice) {
        if (_activeDevice.value?.id == device.id) {
            disconnectInternal()
            _activeDevice.value = null
            _state.value = ConnectionState.Idle
        }
        _knownDevices.value = _knownDevices.value.filterNot { it.id == device.id }
        if (!simulated) store.save(_knownDevices.value)
    }

    fun rename(device: KnownDevice, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _knownDevices.value = _knownDevices.value.map {
            if (it.id == device.id) it.copy(friendlyName = trimmed) else it
        }
        if (_activeDevice.value?.id == device.id) {
            _activeDevice.value = _knownDevices.value.first { it.id == device.id }
        }
        if (!simulated) store.save(_knownDevices.value)
    }

    private fun refineKnownFromDiscovery(address: String, name: String?) {
        if (name.isNullOrEmpty() || name == KnownDevice.PLACEHOLDER_NAME) return
        val idx = _knownDevices.value.indexOfFirst { it.id == address }
        if (idx < 0) return
        val dev = _knownDevices.value[idx]
        if (dev.advertisedName != KnownDevice.PLACEHOLDER_NAME) return
        val refined = dev.copy(
            advertisedName = name,
            friendlyName = if (dev.friendlyName == dev.advertisedName) name else dev.friendlyName,
        )
        _knownDevices.value = _knownDevices.value.toMutableList().also { it[idx] = refined }
        if (_activeDevice.value?.id == address) _activeDevice.value = refined
        store.save(_knownDevices.value)
    }

    private fun upsertKnownAfterConnect(device: BluetoothDevice): KnownDevice {
        val advertised = device.name?.takeIf { it.isNotEmpty() } ?: KnownDevice.PLACEHOLDER_NAME
        val now = System.currentTimeMillis()
        val idx = _knownDevices.value.indexOfFirst { it.id == device.address }
        if (idx >= 0) {
            var dev = _knownDevices.value[idx].copy(lastConnectedMillis = now)
            if (dev.advertisedName == KnownDevice.PLACEHOLDER_NAME &&
                advertised != KnownDevice.PLACEHOLDER_NAME
            ) {
                dev = dev.copy(
                    advertisedName = advertised,
                    friendlyName = if (dev.friendlyName == dev.advertisedName) advertised else dev.friendlyName,
                )
            }
            _knownDevices.value = _knownDevices.value.toMutableList().also { it[idx] = dev }
            store.save(_knownDevices.value)
            return dev
        }
        val new = KnownDevice(device.address, advertised, advertised, now)
        _knownDevices.value = _knownDevices.value + new
        store.save(_knownDevices.value)
        return new
    }

    // MARK: - Read / Write queue

    private sealed interface Op {
        data class Read(val kind: CharKind, val completion: (Result<ByteArray>) -> Unit) : Op
        data class Write(
            val kind: CharKind,
            val value: ByteArray,
            val completion: (Throwable?) -> Unit,
        ) : Op
    }

    private val opQueue = ArrayDeque<Op>()
    private var opInFlight = false

    fun write(kind: CharKind, value: ByteArray, completion: (Throwable?) -> Unit = {}) {
        if (simulated) {
            simWrite(kind, value, completion); return
        }
        if (_state.value !is ConnectionState.Ready) {
            completion(IllegalStateException("not connected")); return
        }
        opQueue.add(Op.Write(kind, value, completion))
        pump()
    }

    fun read(kind: CharKind, completion: (Result<ByteArray>) -> Unit) {
        if (simulated) {
            simRead(kind, completion); return
        }
        if (_state.value !is ConnectionState.Ready) {
            completion(Result.failure(IllegalStateException("not connected"))); return
        }
        opQueue.add(Op.Read(kind, completion))
        pump()
    }

    fun readAll(kinds: List<CharKind>, completion: (Map<CharKind, ByteArray>) -> Unit) {
        val results = mutableMapOf<CharKind, ByteArray>()
        val remaining = ArrayDeque(kinds)
        fun step() {
            val k = remaining.removeFirstOrNull() ?: return completion(results)
            read(k) { r ->
                r.getOrNull()?.let { results[k] = it }
                step()
            }
        }
        step()
    }

    private fun pump() {
        if (opInFlight) return
        val g = gatt ?: return
        val op = opQueue.firstOrNull() ?: return

        // Auth gate: a real write (not the Auth PIN write itself) waits until
        // the link is authed. While bonding is in progress we wait silently;
        // otherwise we ask the UI for the PIN.
        if (op is Op.Write && op.kind != CharKind.Auth && !_authed.value) {
            if (g.device.bondState == BluetoothDevice.BOND_BONDING) return
            _authRequired.tryEmit(Unit)
            return
        }

        val ch = characteristics[op.kindOf()]
        if (ch == null) {
            opQueue.removeFirst()
            val err = IllegalStateException("characteristic ${op.kindOf()} not ready")
            when (op) {
                is Op.Read -> op.completion(Result.failure(err))
                is Op.Write -> op.completion(err)
            }
            pump()
            return
        }

        opInFlight = true
        when (op) {
            is Op.Read -> runCatching { g.readCharacteristic(ch) }
                .onFailure { finishRead(Result.failure(it)) }
            is Op.Write -> writeValue(g, ch, op.value)
        }
    }

    private fun Op.kindOf(): CharKind = when (this) {
        is Op.Read -> kind
        is Op.Write -> kind
    }

    @Suppress("DEPRECATION")
    private fun writeValue(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, type) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = type
            ch.value = value
            g.writeCharacteristic(ch)
        }
        if (!ok) finishWrite(IllegalStateException("write failed to start"))
    }

    private fun finishRead(result: Result<ByteArray>) {
        opInFlight = false
        (opQueue.removeFirstOrNull() as? Op.Read)?.completion?.invoke(result)
        pump()
    }

    private fun finishWrite(error: Throwable?) {
        opInFlight = false
        (opQueue.removeFirstOrNull() as? Op.Write)?.completion?.invoke(error)
        pump()
    }

    /**
     * Write the 6-digit PIN to the Auth characteristic. We optimistically
     * mark the link authed and resume the queue; if the next real write still
     * returns an auth error, [authed] is reverted and [pinRejected] fires.
     */
    fun submitPin(pin: String) {
        if (simulated) {
            _authed.value = true; pump(); return
        }
        val digits = pin.trim()
        opQueue.addFirst(Op.Write(CharKind.Auth, digits.toByteArray(Charsets.UTF_8)) { err ->
            if (err == null) {
                _authed.value = true
            } else {
                _pinRejected.tryEmit(Unit)
            }
        })
        pump()
    }

    private fun isAuthError(status: Int): Boolean =
        status == 5 /* INSUFFICIENT_AUTHENTICATION */ ||
            status == 15 /* INSUFFICIENT_ENCRYPTION */ ||
            status == 137 /* GATT_AUTH_FAIL */

    // MARK: - GATT callback

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    _state.value = ConnectionState.Discovering
                    runCatching { g.discoverServices() }
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    if (gatt === g) gatt = null
                    characteristics.clear()
                    _authed.value = false
                    opInFlight = false
                    opQueue.clear()
                    _activeDevice.value = null
                    _state.value = if (status != BluetoothGatt.GATT_SUCCESS)
                        ConnectionState.Failed("Disconnected") else ConnectionState.Idle
                    val next = pendingNextConnect
                    if (next != null) {
                        pendingNextConnect = null
                        connect(next)
                    } else {
                        reconcileScan()
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            scope.launch {
                val svc = g.getService(CharKind.SERVICE_UUID)
                if (svc == null) {
                    _state.value = ConnectionState.Failed("service not found")
                    return@launch
                }
                characteristics.clear()
                for (ch in svc.characteristics) {
                    CharKind.from(ch.uuid)?.let { characteristics[it] = ch }
                }
                val foundRequired = CharKind.requiredKinds.count { characteristics[it] != null }
                if (foundRequired != CharKind.requiredKinds.size) {
                    _state.value = ConnectionState.Failed(
                        "missing characteristics ($foundRequired/${CharKind.requiredKinds.size})"
                    )
                    reconcileScan()
                    return@launch
                }
                cancelConnectTimeout()
                _activeDevice.value = upsertKnownAfterConnect(g.device)
                _state.value = ConnectionState.Ready
                // Lead with bonding: if not already bonded, ask the OS to pair
                // (the device requests passkey entry → system PIN dialog).
                _authed.value = g.device.bondState == BluetoothDevice.BOND_BONDED
                if (!_authed.value && g.device.bondState == BluetoothDevice.BOND_NONE) {
                    runCatching { g.device.createBond() }
                }
                reconcileScan()
            }
        }

        @Deprecated("Deprecated for API 33+, kept for API 31/32")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            @Suppress("DEPRECATION")
            val value = ch.value ?: ByteArray(0)
            scope.launch { handleReadResult(status, value) }
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int,
        ) {
            scope.launch { handleReadResult(status, value) }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int,
        ) {
            scope.launch {
                val op = opQueue.firstOrNull() as? Op.Write
                if (status != BluetoothGatt.GATT_SUCCESS && isAuthError(status) &&
                    op != null && op.kind != CharKind.Auth
                ) {
                    // Lost / never had auth — revert and re-request the PIN,
                    // keeping the write queued so it retries after auth.
                    _authed.value = false
                    opInFlight = false
                    _authRequired.tryEmit(Unit)
                    pump()
                    return@launch
                }
                val err = if (status == BluetoothGatt.GATT_SUCCESS) null
                else IllegalStateException("write failed (status $status)")
                finishWrite(err)
            }
        }
    }

    private fun handleReadResult(status: Int, value: ByteArray) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            finishRead(Result.success(value))
        } else {
            finishRead(Result.failure(IllegalStateException("read failed (status $status)")))
        }
    }

    // MARK: - Permissions

    private fun hasConnectPermission() =
        appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() =
        appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    /** Called by the Activity once the runtime permissions resolve. */
    fun onPermissionsResolved() {
        if (!simulated) refreshAdapterState()
    }

    fun dispose() {
        if (simulated) return
        runCatching { appContext.unregisterReceiver(receiver) }
        disconnectInternal()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val IN_RANGE_TTL_MS = 10_000L
        private const val PRUNE_INTERVAL_MS = 2_000L

        fun isEmulator(): Boolean {
            val fp = Build.FINGERPRINT
            return fp.startsWith("generic") || fp.startsWith("unknown") ||
                fp.contains("emulator") || fp.contains("sdk_gphone") ||
                Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK built for") ||
                Build.MANUFACTURER.contains("Genymotion") || Build.PRODUCT.contains("sdk")
        }
    }

    // MARK: - Simulation (emulator: no BLE hardware)

    private fun setupSimulatedDevices() {
        val now = System.currentTimeMillis()
        val office = KnownDevice("AA:BB:CC:DD:0F:C1", "Office", "LED-Ticker-0FC1", now - 60_000)
        val home = KnownDevice("AA:BB:CC:DD:A0:E2", "Home", "LED-Ticker-A0E2", now - 3_600_000)
        _knownDevices.value = listOf(office, home)
        _inRange.value = setOf(office.id, home.id)
        _advertisedNames.value = mapOf(
            office.id to office.advertisedName, home.id to home.advertisedName
        )
        simStores[office.id] = mutableMapOf(
            CharKind.Wifi to "OfficeWiFi".toByteArray(),
            CharKind.ApiKey to "sim-finnhub-key".toByteArray(),
            CharKind.Tickers to "AAPL,MSFT,GOOG,AMZN".toByteArray(),
            CharKind.Locations to "47.61,-122.33,Seattle".toByteArray(),
            CharKind.Mode to "all".toByteArray(),
            CharKind.Version to "0.7.1".toByteArray(),
            CharKind.Power to "on".toByteArray(),
            CharKind.Status to ByteArray(0),
            CharKind.Display to "2|70".toByteArray(),
            CharKind.Timezone to "PST8PDT,M3.2.0,M11.1.0".toByteArray(),
        )
        simStores[home.id] = mutableMapOf(
            CharKind.Wifi to "HomeNetwork".toByteArray(),
            CharKind.ApiKey to "sim-finnhub-key".toByteArray(),
            CharKind.Tickers to "TSLA,NVDA,SPY".toByteArray(),
            CharKind.Locations to "30.27,-97.74,Austin|40.75,-73.99,10001".toByteArray(),
            CharKind.Mode to "stocks,clock".toByteArray(),
            CharKind.Version to "0.7.1".toByteArray(),
            CharKind.Power to "on".toByteArray(),
            CharKind.Status to ByteArray(0),
            CharKind.Display to "6|50".toByteArray(),
            CharKind.Timezone to "EST5EDT,M3.2.0,M11.1.0".toByteArray(),
        )
        _state.value = ConnectionState.Idle
    }

    private fun simConnect(device: KnownDevice) {
        if (_activeDevice.value?.id == device.id && _state.value is ConnectionState.Ready) return
        _activeDevice.value = device
        _state.value = ConnectionState.Connecting
        scope.launch {
            delay(400); _state.value = ConnectionState.Discovering
            delay(500)
            _knownDevices.value = _knownDevices.value.map {
                if (it.id == device.id) it.copy(lastConnectedMillis = System.currentTimeMillis()) else it
            }
            _activeDevice.value = _knownDevices.value.firstOrNull { it.id == device.id } ?: device
            _authed.value = true
            _state.value = ConnectionState.Ready
        }
    }

    private fun simDisconnect() {
        _activeDevice.value = null
        _authed.value = false
        _state.value = ConnectionState.Idle
    }

    private fun simWrite(kind: CharKind, value: ByteArray, completion: (Throwable?) -> Unit) {
        val id = _activeDevice.value?.id
        if (_state.value !is ConnectionState.Ready || id == null) {
            completion(IllegalStateException("not connected")); return
        }
        simStores.getOrPut(id) { mutableMapOf() }[kind] = value
        scope.launch { delay(150); completion(null) }
    }

    private fun simRead(kind: CharKind, completion: (Result<ByteArray>) -> Unit) {
        val id = _activeDevice.value?.id
        if (_state.value !is ConnectionState.Ready || id == null) {
            completion(Result.failure(IllegalStateException("not connected"))); return
        }
        scope.launch { delay(100); completion(Result.success(simStores[id]?.get(kind) ?: ByteArray(0))) }
    }
}
