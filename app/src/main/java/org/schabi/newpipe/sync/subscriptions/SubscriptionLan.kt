package org.schabi.newpipe.sync.subscriptions

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.java_websocket.WebSocket
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.SSLParameters

internal data class DiscoveredSubscriptionPeer(
    val id: String, val name: String, val host: String, val port: Int, val serviceName: String
)

internal class SubscriptionLan(
    private val context: Context,
    private val session: SyncSession,
    private val tls: SubscriptionTls,
    private val onDevices: (List<DiscoveredSubscriptionPeer>) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPairing: (String, (Boolean) -> Unit) -> Unit
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exchanges = ConcurrentHashMap<WebSocket, Exchange>()
    private val pairingActions = Executors.newSingleThreadExecutor()
    private val devices = LinkedHashMap<String, DiscoveredSubscriptionPeer>()
    private val resolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var closed = false
    @Volatile private var pairUntil = 0L
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    private var outgoingClient: WebSocketClient? = null

    private val server = object : WebSocketServer(InetSocketAddress(0), listOf(draft())) {
        override fun onStart() {
            val serverPort = this.port
            val service = NsdServiceInfo().apply {
                serviceName = "LeadPipe-" + session.deviceId.take(8)
                serviceType = SERVICE
                port = serverPort
                setAttribute("id", session.deviceId)
                setAttribute("name", Build.MODEL)
            }
            main.post { if (!closed) advertise(service) }
        }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            if (closed) {
                conn.close()
                return
            }
            exchanges[conn] = Exchange(conn, null, false)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            exchanges[conn]?.accept(message)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
            exchanges.remove(conn)?.disconnected()
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null && !closed) status("The LAN listener could not start.")
        }
    }.apply {
        connectionLostTimeout = 30
        setWebSocketFactory(tls.server { !closed &&
            (canPair() || SubscriptionSync.knowsPin(context, it)) })
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            status("LAN discovery failed ($errorCode).")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            main.post { if (!closed) { resolves.addLast(serviceInfo); resolveNext() } }
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            main.post {
                devices.values.removeAll { it.serviceName == serviceInfo.serviceName }
                if (!closed) onDevices(devices.values.toList())
            }
        }
    }

    fun start() {
        server.start()
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    private fun advertise(service: NsdServiceInfo) {
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                status("LAN advertisement failed ($errorCode).")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { status("LAN advertisement failed.") }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || closed) return
        val pending = resolves.pollFirst() ?: return
        resolving = true
        nsd.resolveService(pending, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (closed) return@post
                    val id = serviceInfo.attributes["id"]?.decodeToString()
                    val host = serviceInfo.host?.hostAddress
                    if (id != null && id != session.deviceId && SubscriptionSnapshot.validOrigin(id)
                        && host != null && serviceInfo.port in 1..65535) {
                        val name = serviceInfo.attributes["name"]?.decodeToString()
                            ?.take(100) ?: serviceInfo.serviceName
                        devices[id] = DiscoveredSubscriptionPeer(id, name, host, serviceInfo.port, serviceInfo.serviceName)
                        onDevices(devices.values.toList())
                    }
                    resolveNext()
                }
            }
        })
    }

    fun allowPairing() {
        pairUntil = SystemClock.elapsedRealtime() + PAIR_WINDOW
        status("Ready to pair for two minutes.")
    }

    private fun canPair() = SystemClock.elapsedRealtime() < pairUntil

    fun connect(device: DiscoveredSubscriptionPeer) {
        check(!closed)
        val peer = SubscriptionSync.peer(context, device.id)
        outgoingClient?.close()
        val address = URI("wss", null, device.host, device.port, "/", null, null)
        val client = object : WebSocketClient(address, draft(), null, 4000) {
            lateinit var exchange: Exchange

            // The TrustManager checks the device key. LAN addresses can change after pairing.
            override fun onSetSSLParameters(sslParameters: SSLParameters) = Unit

            override fun onOpen(handshakedata: ServerHandshake) {
                exchange = Exchange(this, device.id, peer == null)
                exchanges[this] = exchange
                exchange.start(peer)
            }

            override fun onMessage(message: String) {
                if (::exchange.isInitialized) exchange.accept(message)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                val opened = exchanges.remove(this)
                if (opened != null) opened.disconnected()
                else if (!closed) status("Could not connect to ${device.name}.")
            }

            override fun onError(ex: Exception) = Unit
        }
        client.setSocketFactory(tls.client(peer?.pin))
        outgoingClient = client
        status("Connecting to ${device.name}…")
        client.connect()
    }

    private fun status(message: String) {
        main.post { if (!closed) onStatus(message) }
    }

    override fun close() {
        closed = true
        outgoingClient?.close()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        runCatching { nsd.stopServiceDiscovery(discovery) }
        pairingActions.shutdownNow()
        Thread({ runCatching { server.stop(500) } }, "LeadPipe subscription listener shutdown").start()
    }

    private inner class Exchange(
        private val socket: WebSocket,
        private val expected: String?,
        private var pairing: Boolean
    ) {
        private var remoteId: String? = null
        private var remotePin: String? = null
        private var sendSnapshot = false
        private var receiveSnapshot = false
        private var receivedSnapshot = false
        private var localApproved = false
        private var remoteApproved = false
        private var localDone = false
        private var remoteDone = false
        private var finished = false
        private var skipped = 0
        private val timeout = Runnable {
            if (socket.isOpen) socket.close(1008, "Subscription session timed out")
        }

        init {
            main.postDelayed(timeout, if (expected == null || pairing) PAIR_WINDOW else 45_000L)
        }

        @Synchronized
        fun start(peer: SubscriptionPeer?) = attempt {
            send("hello") {
                put("id", session.deviceId)
                put("pair", pairing)
                put("send", peer?.send == true)
                put("receive", peer?.receive == true)
            }
        }

        @Synchronized
        fun accept(message: String) = attempt {
            check(!closed)
            val frame = JSONObject(message)
            when (frame.getString("t")) {
                "hello" -> {
                    check(expected == null && remoteId == null)
                    pairing = frame.getBoolean("pair")
                    identify(frame)
                    val peer = pairedPeer()
                    if (pairing) check(canPair() && peer == null) { "Enable pairing on the receiving device." }
                    else check(peer != null) { "Pair this device before syncing." }
                    sendSnapshot = peer?.send == true && frame.getBoolean("receive")
                    receiveSnapshot = peer?.receive == true && frame.getBoolean("send")
                    send("welcome") {
                        put("id", session.deviceId)
                        put("send", sendSnapshot)
                        put("receive", receiveSnapshot)
                    }
                    proceed()
                }
                "welcome" -> {
                    check(expected != null && remoteId == null)
                    identify(frame)
                    if (!pairing) {
                        val peer = requireNotNull(pairedPeer())
                        sendSnapshot = peer.send && frame.getBoolean("receive")
                        receiveSnapshot = peer.receive && frame.getBoolean("send")
                    }
                    proceed()
                }
                "approve" -> {
                    check(pairing && remoteId != null && !remoteApproved)
                    remoteApproved = true
                    completePair()
                }
                "subscriptions" -> {
                    check(!pairing && receiveSnapshot && !receivedSnapshot && !remoteDone)
                    val snapshot = SubscriptionSnapshot.decode(frame.getString("data"))
                    check(snapshot.origin == remoteId) { "The snapshot belongs to a different device." }
                    SubscriptionSync.apply(context, session, snapshot, lan = true)
                    receivedSnapshot = true
                    done()
                }
                "done" -> {
                    check(remoteId != null && !remoteDone)
                    check(if (pairing) remoteApproved else !receiveSnapshot || receivedSnapshot)
                    remoteDone = true
                    complete()
                }
                "error" -> {
                    finished = true
                    status("The peer declined pairing or could not sync.")
                    socket.close()
                }
                else -> error("Unsupported LAN subscription message")
            }
        }

        private fun identify(frame: JSONObject) {
            val id = frame.getString("id")
            check(SubscriptionSnapshot.validOrigin(id) && id != session.deviceId &&
                (expected == null || expected == id)) { "Unexpected device identity" }
            remoteId = id
            remotePin = tls.peerPin(socket)
        }

        private fun pairedPeer(): SubscriptionPeer? {
            val stored = SubscriptionSync.peer(context, requireNotNull(remoteId)) ?: return null
            check(stored.pin == remotePin) { "This device's key changed. Forget it before pairing again." }
            return stored
        }

        private fun proceed() {
            if (pairing) {
                val code = tls.verificationCode(requireNotNull(remotePin))
                main.post {
                    if (!closed && socket.isOpen) onPairing(code) { approved ->
                        if (!closed) runCatching { pairingActions.execute { decide(approved) } }
                    }
                }
                return
            }
            if (sendSnapshot) {
                val outgoing = SubscriptionSync.outgoing(context, session)
                skipped = outgoing.skipped
                send("subscriptions") { put("data", outgoing.snapshot.encode()) }
            }
            if (!receiveSnapshot) done()
        }

        @Synchronized
        private fun decide(approve: Boolean) = attempt {
            check(approve && !closed && socket.isOpen) { "Pairing declined." }
            localApproved = true
            send("approve")
            completePair()
        }

        private fun completePair() {
            if (localApproved && remoteApproved && !localDone) {
                SubscriptionSync.savePeer(context, session,
                    SubscriptionPeer(requireNotNull(remoteId), requireNotNull(remotePin), send = true, receive = true))
                done()
            }
        }

        private fun done() {
            localDone = true
            send("done")
            complete()
        }

        private fun complete() {
            if (!localDone || !remoteDone) return
            finished = true
            main.removeCallbacks(timeout)
            status(when {
                pairing -> "Device paired. Select it again to sync."
                !sendSnapshot && !receiveSnapshot -> "Neither device allowed a subscription transfer."
                skipped > 0 -> "LAN sync complete. Skipped $skipped unsupported subscriptions."
                else -> "LAN subscription sync complete."
            })
            if (expected != null) socket.close()
        }

        @Synchronized
        fun disconnected() {
            main.removeCallbacks(timeout)
            if (!finished) status("The LAN subscription session ended before it completed.")
        }

        private inline fun attempt(block: () -> Unit) {
            if (finished) return
            try {
                block()
            } catch (error: Exception) {
                finished = true
                main.removeCallbacks(timeout)
                status(error.message ?: "LAN subscription sync failed.")
                runCatching { send("error") }
                socket.close(1008, "Subscription exchange refused")
            }
        }

        private fun send(type: String, build: JSONObject.() -> Unit = {}) =
            socket.send(JSONObject().put("t", type).apply(build).toString())
    }

    companion object {
        private const val SERVICE = "_leadpipe-subs._tcp."
        private const val PAIR_WINDOW = 120_000L

        private fun draft() = Draft_6455(emptyList(), listOf(Protocol("leadpipe-subs.v1")),
            SubscriptionSnapshot.MAX_BYTES * 2 + 16_384)
    }
}
