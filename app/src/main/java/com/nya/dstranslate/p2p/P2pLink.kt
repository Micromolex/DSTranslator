package com.nya.dstranslate.p2p

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.nya.dstranslate.data.Lang
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Роли в связке двух телефонов.
 *
 * HOST создаёт комнату и ждёт входящее соединение, CLIENT ищет комнату
 * и подключается к ней. Роль влияет только на то, кто поднимает сервер:
 * данные после соединения идут в обе стороны.
 */
enum class P2pRole { NONE, HOST, CLIENT }

/** Канал связи: основной Wi-Fi Direct, резервный Bluetooth RFCOMM. */
enum class P2pTransport { NONE, WIFI_DIRECT, BLUETOOTH }

/** Состояние связки, по которому строится подпись в интерфейсе. */
enum class P2pStatus { IDLE, SEARCHING, WAITING, CONNECTING, CONNECTED, ERROR }

/** Машинные причины ошибок — интерфейс переводит их на свой язык. */
enum class P2pFailure {
    PERMISSIONS,
    WIFI_DIRECT_UNAVAILABLE,
    WIFI_DIRECT_OFF,
    BLUETOOTH_UNAVAILABLE,
    BLUETOOTH_OFF,
    DISCOVERY_FAILED,
    CONNECT_FAILED,
    CONNECTION_LOST,
    SOCKET_FAILED,
    UNKNOWN
}

/** Найденное устройство в списке «Подключиться». */
data class P2pPeer(
    val name: String,
    val address: String,
    val wifiDirect: Boolean
)

/**
 * Одно сообщение связки.
 *
 * @param sender язык, на котором говорил отправитель;
 * @param original распознанный текст отправителя;
 * @param translated перевод на язык собеседника;
 * @param outgoing true — сообщение отправлено этим телефоном.
 */
data class P2pMessage(
    val sender: Lang,
    val original: String,
    val translated: String,
    val outgoing: Boolean,
    val at: Long,
    val transport: P2pTransport
)

/** Слушатель событий связки; все методы вызываются в главном потоке. */
interface P2pListener {
    fun onState(
        status: P2pStatus,
        transport: P2pTransport,
        role: P2pRole,
        failure: P2pFailure?,
        detail: String?
    )

    fun onPeers(peers: List<P2pPeer>)
    fun onMessage(message: P2pMessage)
}

/**
 * Помощник по разрешениям Android 12+.
 *
 * Список собирается по версии системы: на 33+ нужен NEARBY_WIFI_DEVICES,
 * на 31+ — BLUETOOTH_SCAN/CONNECT/ADVERTISE; на старых системах —
 * BLUETOOTH/BLUETOOTH_ADMIN. ACCESS_FINE_LOCATION запрашивается всегда:
 * без него Wi-Fi Direct не находит устройства на Android 10–12.
 */
object P2pPermissions {

    /** Полный список для первого запроса у пользователя. */
    fun required(): List<String> {
        val list = mutableListOf<String>()
        // Геолокация нужна поиску Wi-Fi Direct до Android 13 включительно.
        // На 13+ вместо неё работает NEARBY_WIFI_DEVICES, но запросить её
        // всё равно полезно: приложение не должно зависеть от версии системы.
        list += android.Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += android.Manifest.permission.BLUETOOTH_SCAN
            list += android.Manifest.permission.BLUETOOTH_CONNECT
            // Без ADVERTISE система не даёт сделать телефон видимым для Bluetooth.
            list += android.Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            list += android.Manifest.permission.BLUETOOTH
            list += android.Manifest.permission.BLUETOOTH_ADMIN
        }
        return list
    }

    /**
     * Что ещё не выдано из [required] — для первого запроса и кнопки «Разрешить».
     */
    fun requestList(context: Context): List<String> =
        required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * Чего реально не хватает для работы обоих каналов.
     *
     * На Android 13+ точная геолокация для Wi-Fi Direct уже не обязательна,
     * поэтому в этот список она не попадает: иначе отказ от геолокации
     * блокировал бы Bluetooth, которому она не нужна.
     */
    fun missing(context: Context): List<String> =
        (missingWifi(context) + missingBluetooth(context)).distinct()

    /** Разрешения именно для Wi-Fi Direct. */
    fun missingWifi(context: Context): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            list += android.Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            list += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return list.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /** Разрешения именно для Bluetooth RFCOMM. */
    fun missingBluetooth(context: Context): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += android.Manifest.permission.BLUETOOTH_SCAN
            list += android.Manifest.permission.BLUETOOTH_CONNECT
            list += android.Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            list += android.Manifest.permission.BLUETOOTH
            list += android.Manifest.permission.BLUETOOTH_ADMIN
        }
        return list.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun granted(context: Context): Boolean = missing(context).isEmpty()
}

/**
 * Связка двух телефонов без интернета и без Google Play Services.
 *
 * Основной канал — Wi-Fi Direct: хост создаёт группу (Group Owner) и слушает
 * TCP-порт, клиент подключается к адресу владельца группы. Если Wi-Fi Direct
 * на устройстве недоступен (или выбран вручную), используется Bluetooth
 * RFCOMM с тем же протоколом: каждая строка — JSON-объект.
 *
 * Класс не знает про Compose: состояние отдаётся через [P2pListener].
 */
@SuppressLint("MissingPermission")
class P2pLink(
    context: Context,
    private val listener: P2pListener
) {

    companion object {
        private const val TAG = "P2pLink"

        /** Имя службы RFCOMM; UUID — стандартный SPP, его понимают все телефоны. */
        private const val BT_SERVICE_NAME = "DSTranslate P2P"
        private val BT_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Порты TCP по очереди: первый свободный занимает хост, клиент их перебирает. */
        private val PORTS = intArrayOf(8988, 8989, 8990, 8991)

        /** Максимальная длина одной строки протокола — защита от мусора в сокете. */
        private const val MAX_LINE = 64 * 1024
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /** Wi-Fi Direct инициализируется лениво: не трогаем радио без запроса. */
    private var wifiManager: WifiP2pManager? = null
    private var wifiChannel: WifiP2pManager.Channel? = null
    private var wifiReceiverRegistered = false
    private var btReceiverRegistered = false

    private var role: P2pRole = P2pRole.NONE
    private var transport: P2pTransport = P2pTransport.NONE

    @Volatile
    private var status: P2pStatus = P2pStatus.IDLE

    /** Сокеты и потоки; доступ к записи сериализован по [outLock]. */
    private var tcpSocket: Socket? = null
    private var tcpServer: ServerSocket? = null
    private var btSocket: BluetoothSocket? = null
    private var btServer: BluetoothServerSocket? = null
    private var outStream: OutputStream? = null
    private val outLock = Any()

    private var acceptThread: Thread? = null
    private var readThread: Thread? = null

    /** Защита от двойного запуска TCP-сервера/клиента по повторному broadcast. */
    @Volatile
    private var tcpServerStarting = false
    @Volatile
    private var tcpClientConnecting = false

    /** Поколение сеанса: старые колбэки после «Отключиться» ничего не меняют. */
    @Volatile
    private var session = 0

    // ---------------------------------------------------------------- старт

    /** Wi-Fi Direct есть в железе и менеджер доступен. */
    fun wifiDirectAvailable(): Boolean {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            return false
        }
        return appContext.getSystemService(Context.WIFI_P2P_SERVICE) is WifiP2pManager
    }

    /** Bluetooth-адаптер есть в системе. */
    fun bluetoothAvailable(): Boolean = bluetoothAdapter() != null

    /**
     * Создать комнату.
     *
     * @param preferBluetooth true — сразу RFCOMM (ручной выбор или нет Wi-Fi Direct).
     */
    fun startHost(preferBluetooth: Boolean) {
        reset()
        role = P2pRole.HOST
        val useBt = preferBluetooth || !wifiDirectAvailable()
        transport = if (useBt) P2pTransport.BLUETOOTH else P2pTransport.WIFI_DIRECT
        // Проверяем разрешения до включения радио: отказ должен быть понятным,
        // а не «поиск ничего не нашёл».
        val missing = if (useBt) {
            P2pPermissions.missingBluetooth(appContext)
        } else {
            P2pPermissions.missingWifi(appContext)
        }
        if (missing.isNotEmpty()) {
            fail(P2pFailure.PERMISSIONS, missing.joinToString())
            return
        }
        if (useBt) startBluetoothHost() else startWifiDirectHost()
    }

    /** Начать поиск комнаты и подключиться к выбранному устройству. */
    fun startClient(preferBluetooth: Boolean) {
        reset()
        role = P2pRole.CLIENT
        val useBt = preferBluetooth || !wifiDirectAvailable()
        transport = if (useBt) P2pTransport.BLUETOOTH else P2pTransport.WIFI_DIRECT
        val missing = if (useBt) {
            P2pPermissions.missingBluetooth(appContext)
        } else {
            P2pPermissions.missingWifi(appContext)
        }
        if (missing.isNotEmpty()) {
            fail(P2pFailure.PERMISSIONS, missing.joinToString())
            return
        }
        if (useBt) startBluetoothClient() else startWifiDirectClient()
    }

    /** Обновить список устройств (повторный поиск). */
    fun rescan() {
        when (transport) {
            P2pTransport.WIFI_DIRECT -> if (role == P2pRole.CLIENT) startWifiDirectDiscovery()
            P2pTransport.BLUETOOTH -> if (role == P2pRole.CLIENT) startBluetoothDiscovery()
            P2pTransport.NONE -> Unit
        }
    }

    /** Подключиться к найденному устройству из списка. */
    fun connectPeer(peer: P2pPeer) {
        when {
            peer.wifiDirect -> connectWifiPeer(peer.address)
            else -> connectBluetoothPeer(peer.address)
        }
    }

    // ------------------------------------------------------------ Wi-Fi Direct

    private fun ensureWifiManager(): WifiP2pManager? {
        val mgr = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (mgr == null) {
            fail(P2pFailure.WIFI_DIRECT_UNAVAILABLE, "WifiP2pManager отсутствует")
            return null
        }
        if (wifiChannel == null) {
            wifiChannel = mgr.initialize(appContext, Looper.getMainLooper()) {
                Log.w(TAG, "Wi-Fi Direct: канал потерян")
            }
        }
        wifiManager = mgr
        registerWifiReceiver()
        return mgr
    }

    private fun startWifiDirectHost() {
        val mgr = ensureWifiManager() ?: return
        val channel = wifiChannel ?: return
        transport = P2pTransport.WIFI_DIRECT
        publish(P2pStatus.WAITING, detail = "Wi-Fi Direct")
        // Группа делает телефон Group Owner: клиенту останется только подключиться.
        mgr.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct: группа создана, ждём клиента")
                publish(P2pStatus.WAITING, detail = "Wi-Fi Direct")
                // Сервер поднимаем сразу, не дожидаясь broadcast: на части
                // прошивок событие о группе приходит позже, чем клиент успевает
                // подключиться. Повторный вызов защищён tcpServerStarting.
                startTcpServer()
                // Поиск нужен, чтобы в списке соседей группа была видна.
                mgr.discoverPeers(channel, noopAction("discoverPeers"))
            }

            override fun onFailure(reason: Int) {
                // Группа уже могла существовать с прошлого раза — сносим и пробуем снова.
                mgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = createWifiGroupAgain(mgr, channel)
                    override fun onFailure(reason2: Int) =
                        fail(P2pFailure.CONNECT_FAILED, "createGroup: $reason")
                })
            }
        })
    }

    private fun createWifiGroupAgain(mgr: WifiP2pManager, channel: WifiP2pManager.Channel) {
        mgr.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                publish(P2pStatus.WAITING, detail = "Wi-Fi Direct")
                startTcpServer()
            }

            override fun onFailure(reason: Int) {
                // Wi-Fi Direct не смог — сразу предлагаем резервный Bluetooth.
                fail(P2pFailure.WIFI_DIRECT_UNAVAILABLE, "createGroup: $reason")
            }
        })
    }

    private fun startWifiDirectClient() {
        ensureWifiManager() ?: return
        transport = P2pTransport.WIFI_DIRECT
        publish(P2pStatus.SEARCHING, detail = "Wi-Fi Direct")
        startWifiDirectDiscovery()
    }

    private fun startWifiDirectDiscovery() {
        val mgr = wifiManager ?: return
        val channel = wifiChannel ?: return
        transport = P2pTransport.WIFI_DIRECT
        publish(P2pStatus.SEARCHING, detail = "Wi-Fi Direct")
        mgr.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Список придёт широковещательным WIFI_P2P_PEERS_CHANGED_ACTION.
                Log.i(TAG, "Wi-Fi Direct: поиск устройств запущен")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct: discoverPeers failed=$reason")
                fail(P2pFailure.DISCOVERY_FAILED, "discoverPeers: $reason")
            }
        })
    }

    private fun connectWifiPeer(address: String) {
        val mgr = wifiManager ?: return
        val channel = wifiChannel ?: return
        if (address.isBlank()) return
        publish(P2pStatus.CONNECTING, detail = address)
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            // PBC — самый совместимый способ: пароль вводить не нужно.
            wps.setup = WpsInfo.PBC
            // 0 — этот телефон не претендует на роль Group Owner.
            groupOwnerIntent = 0
        }
        mgr.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct: connect отправлен на $address")
            }

            override fun onFailure(reason: Int) {
                fail(P2pFailure.CONNECT_FAILED, "connect: $reason")
            }
        })
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            fail(P2pFailure.WIFI_DIRECT_OFF, "Wi-Fi Direct выключен")
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Список соседей запрашиваем только клиенту: хосту он не нужен.
                        if (role == P2pRole.CLIENT) requestPeers()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        // Соединение группы могли разорвать извне — проверяем по факту.
                        onWifiConnectionChanged()
                    }
                }
            } catch (e: Exception) {
                // Разрешение могли отозвать в настройках уже во время работы.
                Log.w(TAG, "Wi-Fi Direct broadcast: ${e.message}")
                fail(P2pFailure.PERMISSIONS, e.message)
            }
        }
    }

    private fun requestPeers() {
        val mgr = wifiManager ?: return
        val channel = wifiChannel ?: return
        val mySession = session
        mgr.requestPeers(channel) { peerList ->
            // Пока система собирала список, пользователь мог нажать «Отключиться».
            if (mySession == session) {
                val peers = peerList.deviceList
                    .filter { it.status != WifiP2pDevice.UNAVAILABLE }
                    .map {
                        P2pPeer(
                            // Пустое имя бывает у чужих устройств — показываем адрес.
                            name = it.deviceName.ifBlank { it.deviceAddress },
                            address = it.deviceAddress,
                            wifiDirect = true
                        )
                    }
                    .sortedBy { it.name.lowercase() }
                listener.onPeers(peers)
            }
        }
    }

    private fun onWifiConnectionChanged() {
        val mgr = wifiManager ?: return
        val channel = wifiChannel ?: return
        val mySession = session
        mgr.requestConnectionInfo(channel) { info ->
            if (mySession != session) return@requestConnectionInfo
            if (!info.groupFormed) {
                // Группа распалась после успешного соединения — сообщаем об обрыве.
                if (status == P2pStatus.CONNECTED) {
                    fail(P2pFailure.CONNECTION_LOST, "группа Wi-Fi Direct распалась")
                }
                return@requestConnectionInfo
            }
            if (role == P2pRole.HOST) {
                // Хосту адрес не нужен: сервер слушает на всех интерфейсах.
                // Пока связь установлена, второй сервер не поднимаем.
                if (tcpServer == null && status != P2pStatus.CONNECTED) startTcpServer()
            } else if (info.isGroupOwner) {
                // Клиент неожиданно стал владельцем группы: TCP-клиент некуда идти.
                fail(P2pFailure.CONNECT_FAILED, "клиент назначен Group Owner")
            } else {
                connectTcpClient(info.groupOwnerAddress.hostAddress.orEmpty())
            }
        }
    }

    // ------------------------------------------------------------- TCP-канал

    private fun startTcpServer() {
        // Уже подключены — второй сервер не нужен и занял бы порт.
        if (status == P2pStatus.CONNECTED) return
        // Повторный broadcast о соединении не должен поднять второй сервер:
        // два ServerSocket на разных портах означали бы два разных канала.
        if (tcpServerStarting || tcpServer != null) return
        tcpServerStarting = true
        val mySession = session
        acceptThread = Thread {
            try {
                for (port in PORTS) {
                    if (mySession != session) return@Thread
                    try {
                        val server = ServerSocket(port)
                        tcpServer = server
                        // 0.0.0.0 — слушаем и на интерфейсе Wi-Fi Direct.
                        Log.i(TAG, "TCP: сервер слушает порт $port")
                        var client: Socket? = null
                        // Принимаем только адрес из подсети Wi-Fi Direct
                        // (Group Owner — 192.168.49.1): иначе к открытому порту
                        // мог бы подключиться кто угодно из домашней сети.
                        while (client == null) {
                            val accepted = server.accept()
                            val peer = accepted.inetAddress?.hostAddress.orEmpty()
                            if (peer.startsWith("192.168.49.")) {
                                client = accepted
                            } else {
                                Log.w(TAG, "TCP: отклонён чужой адрес $peer")
                                try {
                                    accepted.close()
                                } catch (_: Exception) {
                                }
                            }
                        }
                        val connected = client ?: continue
                        Log.i(TAG, "TCP: клиент подключился ${connected.inetAddress}")
                        // Сервер нужен только для одного клиента — освобождаем порт.
                        try {
                            server.close()
                        } catch (_: Exception) {
                        }
                        tcpServer = null
                        addTcpSocket(connected, mySession)
                        return@Thread
                    } catch (e: Exception) {
                        // Порт занят — пробуем следующий из списка.
                        Log.w(TAG, "TCP: порт $port не подошёл: ${e.message}")
                    }
                }
                if (mySession == session) {
                    fail(P2pFailure.SOCKET_FAILED, "не удалось открыть TCP-порт")
                }
            } finally {
                tcpServerStarting = false
            }
        }.apply {
            name = "p2p-tcp-server"
            isDaemon = true
            start()
        }
    }

    private fun connectTcpClient(host: String) {
        if (host.isBlank()) {
            fail(P2pFailure.SOCKET_FAILED, "неизвестный адрес хоста")
            return
        }
        // Пока идут попытки, повторный запрос соединения игнорируем.
        if (tcpClientConnecting) return
        tcpClientConnecting = true
        val mySession = session
        publish(P2pStatus.CONNECTING, detail = host)
        Thread {
            try {
                // После создания группы сеть поднимается не мгновенно — пробуем несколько раз.
                repeat(8) { attempt ->
                    if (mySession != session) return@Thread
                    for (port in PORTS) {
                        try {
                            val socket = Socket()
                            socket.connect(InetSocketAddress(host, port), 2500)
                            addTcpSocket(socket, mySession)
                            return@Thread
                        } catch (e: Exception) {
                            Log.w(TAG, "TCP: $host:$port попытка ${attempt + 1} — ${e.message}")
                        }
                    }
                    try {
                        Thread.sleep(700)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
                if (mySession == session) fail(P2pFailure.CONNECT_FAILED, "TCP $host")
            } finally {
                tcpClientConnecting = false
            }
        }.apply {
            name = "p2p-tcp-client"
            isDaemon = true
            start()
        }
    }

    private fun addTcpSocket(socket: Socket, mySession: Int) {
        if (mySession != session) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        tcpSocket = socket
        try {
            socket.tcpNoDelay = true
        } catch (_: Exception) {
        }
        val out = socket.getOutputStream()
        outStream = out
        publish(P2pStatus.CONNECTED, detail = socket.inetAddress?.hostAddress ?: "Wi-Fi Direct")
        startReadLoop(BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)))
    }

    // --------------------------------------------------------------- Bluetooth

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun startBluetoothHost() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            fail(P2pFailure.BLUETOOTH_UNAVAILABLE, "BluetoothAdapter отсутствует")
            return
        }
        transport = P2pTransport.BLUETOOTH
        // Разрешения проверяем до adapter.isEnabled: на Android 12+ даже
        // чтение состояния адаптера требует BLUETOOTH_CONNECT.
        if (P2pPermissions.missingBluetooth(appContext).isNotEmpty()) {
            fail(P2pFailure.PERMISSIONS, "нет разрешений Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            fail(P2pFailure.BLUETOOTH_OFF, "Bluetooth выключен")
            return
        }
        publish(P2pStatus.WAITING, detail = adapter.name ?: "Bluetooth")
        val mySession = session
        acceptThread = Thread {
            try {
                // Служба RFCOMM видна ищущим телефонам, пока приложение в foreground.
                val server = adapter.listenUsingRfcommWithServiceRecord(BT_SERVICE_NAME, BT_UUID)
                btServer = server
                Log.i(TAG, "BT: сервер слушает RFCOMM")
                val client = server.accept()
                Log.i(TAG, "BT: клиент подключился ${client.remoteDevice?.address}")
                try {
                    server.close()
                } catch (_: Exception) {
                }
                btServer = null
                addBtSocket(client, mySession)
            } catch (e: Exception) {
                if (mySession == session) {
                    Log.w(TAG, "BT: сервер остановлен: ${e.message}")
                    if (status != P2pStatus.CONNECTED) {
                        fail(P2pFailure.SOCKET_FAILED, e.message)
                    }
                }
            }
        }.apply {
            name = "p2p-bt-server"
            isDaemon = true
            start()
        }
    }

    private fun startBluetoothClient() {
        val adapter = bluetoothAdapter()
        if (adapter == null) {
            fail(P2pFailure.BLUETOOTH_UNAVAILABLE, "BluetoothAdapter отсутствует")
            return
        }
        transport = P2pTransport.BLUETOOTH
        if (P2pPermissions.missingBluetooth(appContext).isNotEmpty()) {
            fail(P2pFailure.PERMISSIONS, "нет разрешений Bluetooth")
            return
        }
        if (!adapter.isEnabled) {
            fail(P2pFailure.BLUETOOTH_OFF, "Bluetooth выключен")
            return
        }
        // Сначала показываем уже сопряжённые устройства — подключение к ним быстрее.
        publishBondedPeers(adapter)
        startBluetoothDiscovery()
    }

    private fun publishBondedPeers(adapter: BluetoothAdapter) {
        val peers = try {
            adapter.bondedDevices.orEmpty().map {
                P2pPeer(it.name ?: it.address, it.address, wifiDirect = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "BT: список сопряжённых недоступен: ${e.message}")
            emptyList()
        }
        listener.onPeers(peers.sortedBy { it.name.lowercase() })
    }

    private fun startBluetoothDiscovery() {
        val adapter = bluetoothAdapter() ?: return
        registerBtReceiver()
        // cancelDiscovery перед новым поиском: иначе startDiscovery вернёт false.
        try {
            adapter.cancelDiscovery()
        } catch (_: Exception) {
        }
        val started = try {
            adapter.startDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "BT: startDiscovery: ${e.message}")
            false
        }
        if (started) {
            publish(P2pStatus.SEARCHING, detail = "Bluetooth")
        } else {
            fail(P2pFailure.DISCOVERY_FAILED, "startDiscovery")
        }
    }

    private fun connectBluetoothPeer(address: String) {
        val adapter = bluetoothAdapter() ?: return
        if (address.isBlank()) return
        try {
            adapter.cancelDiscovery()
        } catch (_: Exception) {
        }
        transport = P2pTransport.BLUETOOTH
        publish(P2pStatus.CONNECTING, detail = address)
        val mySession = session
        Thread {
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                fail(P2pFailure.CONNECT_FAILED, "адрес $address")
                return@Thread
            }
            try {
                val socket = device.createRfcommSocketToServiceRecord(BT_UUID)
                // 4 с — типичный таймаут RFCOMM; без него телефон «висит» минутами.
                socket.connect()
                addBtSocket(socket, mySession)
            } catch (e: Exception) {
                Log.w(TAG, "BT: подключение к $address не удалось: ${e.message}")
                if (mySession == session) fail(P2pFailure.CONNECT_FAILED, e.message)
            }
        }.apply {
            name = "p2p-bt-client"
            isDaemon = true
            start()
        }
    }

    private fun addBtSocket(socket: BluetoothSocket, mySession: Int) {
        if (mySession != session) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
            return
        }
        btSocket = socket
        outStream = socket.outputStream
        publish(
            P2pStatus.CONNECTED,
            detail = socket.remoteDevice?.name ?: socket.remoteDevice?.address ?: "Bluetooth"
        )
        startReadLoop(BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8)))
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        ?: return
                    val name = try {
                        device.name
                    } catch (e: SecurityException) {
                        null
                    }
                    // Безымянные устройства бесполезны в списке: у них нет подписи.
                    if (name.isNullOrBlank()) return
                    listener.onPeers(
                        listOf(P2pPeer(name, device.address, wifiDirect = false))
                    )
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Поиск закончился, но найденные устройства уже в списке;
                    // статус оставляем «поиск», чтобы можно было обновить вручную.
                    Log.i(TAG, "BT: поиск завершён")
                }
            }
        }
    }

    // -------------------------------------------------------------- протокол

    private fun startReadLoop(reader: BufferedReader) {
        val mySession = session
        readThread = Thread {
            try {
                while (mySession == session) {
                    val line = reader.readLine() ?: break
                    if (line.length > MAX_LINE) continue
                    parseLine(line)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Связь: чтение прервано: ${e.message}")
            }
            if (mySession == session && status == P2pStatus.CONNECTED) {
                fail(P2pFailure.CONNECTION_LOST, "собеседник отключился")
            }
        }.apply {
            name = "p2p-reader"
            isDaemon = true
            start()
        }
    }

    private fun parseLine(line: String) {
        try {
            val obj = JSONObject(line)
            if (obj.optString("type") != "message") return
            val sender = if (obj.optString("sender") == Lang.ZH.tag) Lang.ZH else Lang.RU
            val original = obj.optString("original")
            val translated = obj.optString("translated")
            if (original.isBlank() && translated.isBlank()) return
            val message = P2pMessage(
                sender = sender,
                original = original,
                translated = translated,
                outgoing = false,
                at = System.currentTimeMillis(),
                transport = transport
            )
            main.post { listener.onMessage(message) }
        } catch (e: Exception) {
            // Битая строка не должна рвать соединение: просто пропускаем.
            Log.w(TAG, "Связь: пропущена строка: ${e.message}")
        }
    }

    /**
     * Отправить JSON-сообщение. Возвращает false, если канал ещё не готов.
     *
     * Формат: {"type":"message","sender":"ru","original":"…","translated":"…"}.
     */
    fun send(sender: Lang, original: String, translated: String): Boolean {
        val out = outStream ?: return false
        if (status != P2pStatus.CONNECTED) return false
        val obj = JSONObject()
            .put("type", "message")
            .put("sender", sender.tag)
            .put("original", original)
            .put("translated", translated)
            .put("ts", System.currentTimeMillis())
        val bytes = (obj.toString() + "\n").toByteArray(Charsets.UTF_8)
        return try {
            synchronized(outLock) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Связь: отправка не удалась: ${e.message}")
            fail(P2pFailure.CONNECTION_LOST, e.message)
            false
        }
    }

    // -------------------------------------------------------------- остановка

    /** Полный сброс: закрыть сокеты, снять регистрацию приёмников, выйти из группы. */
    fun stop() {
        reset()
        publish(P2pStatus.IDLE)
    }

    private fun reset() {
        session++
        tcpServerStarting = false
        tcpClientConnecting = false
        role = P2pRole.NONE
        transport = P2pTransport.NONE
        outStream = null
        closeQuietly(tcpSocket)
        closeQuietly(tcpServer)
        closeQuietly(btSocket)
        closeQuietly(btServer)
        tcpSocket = null
        tcpServer = null
        btSocket = null
        btServer = null
        // Потоки завершатся сами по закрытым сокетам; ждать их не нужно.
        acceptThread = null
        readThread = null
        unregisterWifiReceiver()
        unregisterBtReceiver()
        val mgr = wifiManager
        val channel = wifiChannel
        if (mgr != null && channel != null) {
            // Без разрешения NEARBY_WIFI_DEVICES removeGroup бросает
            // SecurityException; остановка связи не должна из-за этого падать.
            try {
                mgr.removeGroup(channel, noopAction("removeGroup"))
            } catch (e: Exception) {
                Log.w(TAG, "Wi-Fi Direct: removeGroup: ${e.message}")
            }
        }
        try {
            bluetoothAdapter()?.cancelDiscovery()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(closeable: java.io.Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- события

    private fun noopAction(tag: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            Log.d(TAG, "Wi-Fi Direct: $tag ok")
        }

        override fun onFailure(reason: Int) {
            Log.d(TAG, "Wi-Fi Direct: $tag fail=$reason")
        }
    }

    private fun publish(
        newStatus: P2pStatus,
        failure: P2pFailure? = null,
        detail: String? = null
    ) {
        status = newStatus
        val transportNow = transport
        val roleNow = role
        main.post { listener.onState(newStatus, transportNow, roleNow, failure, detail) }
    }

    private fun fail(failure: P2pFailure, detail: String?) {
        Log.w(TAG, "Связь: ошибка $failure ($detail)")
        status = P2pStatus.ERROR
        val transportNow = transport
        val roleNow = role
        main.post { listener.onState(P2pStatus.ERROR, transportNow, roleNow, failure, detail) }
    }

    // -------------------------------------------------------- приёмники системы

    private fun registerWifiReceiver() {
        if (wifiReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        try {
            // Системные broadcast'ы Wi-Fi Direct обязаны быть EXPORTED,
            // иначе на Android 14 приложение не получит их вовсе.
            ContextCompat.registerReceiver(
                appContext,
                wifiReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            wifiReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось зарегистрировать Wi-Fi Direct: ${e.message}")
        }
    }

    private fun unregisterWifiReceiver() {
        if (!wifiReceiverRegistered) return
        try {
            appContext.unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
        wifiReceiverRegistered = false
    }

    private fun registerBtReceiver() {
        if (btReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            ContextCompat.registerReceiver(
                appContext,
                btReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            btReceiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось зарегистрировать Bluetooth: ${e.message}")
        }
    }

    private fun unregisterBtReceiver() {
        if (!btReceiverRegistered) return
        try {
            appContext.unregisterReceiver(btReceiver)
        } catch (_: Exception) {
        }
        btReceiverRegistered = false
    }
}
