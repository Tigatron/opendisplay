package com.terrynamic.opendisplay.transport

import android.os.SystemClock
import android.util.Log
import com.terrynamic.opendisplay.protocol.WireProtocol
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ReceiverListener(
    private val port: Int = WireProtocol.DEFAULT_PORT,
    private val helloProvider: (InetAddress?) -> JSONObject,
    private val onSession: SessionCallbacks,
) {
    interface SessionCallbacks {
        /** Reset session state and return the frame handler before the reader starts. */
        fun onAdopted(
            connection: FramedConnection,
            generation: Long,
            initialBytes: ByteArray,
            transport: String,
        ): (ByteArray) -> Unit
        fun onSessionClosed(generation: Long)
        fun onListening(bound: Boolean)
        fun onWatchdog()
    }

    private val running = AtomicBoolean(false)
    private val machine = NewcomerMachine()
    private val machineLock = Any()
    private val bindLock = ReentrantLock()
    private val bindChanged = bindLock.newCondition()
    private val generation = AtomicLong(0)
    private val sockets = ConcurrentHashMap<Long, Socket>()
    private val timeouts = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private var acceptThread: Thread? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    private var listen: ListenBind? = null

    @Volatile
    private var desiredHost: String = ListenerGate.WILDCARD
    private var retryAttempt = 0
    private var retryFuture: ScheduledFuture<*>? = null
    private var lastBindError: String? = null
    private val listenGeneration = AtomicLong(0)

    @Volatile
    var liveConnection: LiveSession? = null
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "od-park").apply { isDaemon = true }
        }
        rebind(ListenerGate.WILDCARD)
        acceptThread = Thread({ acceptLoop() }, "od-accept").apply {
            isDaemon = true
            start()
        }
        onSession.onListening(true)
        Log.i(WireProtocol.LOG_TAG, "listening on :$port")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        bindLock.withLock {
            retryFuture?.cancel(false)
            retryFuture = null
            try {
                listen?.server?.close()
            } catch (_: Exception) {
            }
            listen = null
            bindChanged.signalAll()
        }
        acceptThread?.interrupt()
        acceptThread = null
        timeouts.values.forEach { it.cancel(true) }
        timeouts.clear()
        sockets.values.forEach { closeQuietly(it) }
        sockets.clear()
        liveConnection?.close()
        liveConnection = null
        synchronized(machineLock) { machine.reset() }
        scheduler?.shutdownNow()
        scheduler = null
        onSession.onListening(false)
    }

    fun enqueue(message: OutboundMessage) {
        liveConnection?.outbound?.offer(message)
    }

    fun sendControl(kind: OutboundKind, json: JSONObject) {
        enqueue(OutboundMessage(kind, json.toString().toByteArray(Charsets.UTF_8)))
    }

    private fun acceptLoop() {
        while (running.get()) {
            val snap = waitForOpenListen() ?: continue
            try {
                val socket = snap.server.accept()
                val current = listen
                if (current == null ||
                    !ListenerGate.acceptIsCurrent(snap.generation, current.generation) ||
                    current.server !== snap.server
                ) {
                    Log.i(WireProtocol.LOG_TAG, "stale accept after listener swap — closed")
                    closeQuietly(socket)
                    continue
                }
                FramedConnection.configureSocket(socket)
                val id = generation.incrementAndGet()
                sockets[id] = socket
                Log.i(WireProtocol.LOG_TAG, "new connection from ${socket.inetAddress?.hostAddress}")
                val actions = synchronized(machineLock) {
                    machine.onAccept(id, SystemClock.elapsedRealtime())
                }
                applyActions(actions, initialSocket = socket)
            } catch (e: IOException) {
                if (!running.get()) break
                val swappedOrClosed = snap.server.isClosed || listen?.server !== snap.server
                if (!swappedOrClosed) {
                    Log.w(
                        WireProtocol.LOG_TAG,
                        "accept failed: ${e.javaClass.name}: ${e.message}",
                    )
                    bindLock.withLock {
                        try {
                            bindChanged.await(50, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        }
    }

    private fun waitForOpenListen(): ListenBind? {
        bindLock.withLock {
            while (running.get()) {
                val current = listen
                if (!ListenerSwapPolicy.acceptMustWait(
                        listenNull = current == null,
                        serverClosed = current?.server?.isClosed == true,
                    )
                ) {
                    return current
                }
                try {
                    bindChanged.await(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            return null
        }
    }

    private fun syncBindToLiveSession() {
        val live = liveConnection
        val host = ListenerGate.bindHost(
            ListenerGate.SessionState(
                hasLiveSession = live != null,
                transport = live?.transport,
            ),
        )
        rebind(host)
    }

    private fun rebind(host: String) {
        bindLock.withLock {
            if (!running.get()) return
            desiredHost = host
            val current = listen
            val snapshot = ListenerSwapPolicy.Snapshot(
                host = current?.host,
                open = current != null && !current.server.isClosed,
            )
            when (val plan = ListenerSwapPolicy.plan(snapshot, host)) {
                is ListenerSwapPolicy.Plan.AlreadyBound -> {
                    cancelRetryLocked()
                    retryAttempt = 0
                }
                is ListenerSwapPolicy.Plan.BindFresh -> {
                    installListenLocked(tryOpen(plan.host), boundHost = plan.host, desired = host)
                }
                is ListenerSwapPolicy.Plan.CloseThenBind -> {
                    closeListenSocketLocked(current)
                    listen = null
                    bindChanged.signalAll()
                    val desiredSocket = tryOpen(plan.desired)
                    if (desiredSocket != null) {
                        installListenLocked(desiredSocket, boundHost = plan.desired, desired = host)
                    } else {
                        installListenLocked(tryOpen(plan.fallback), boundHost = plan.fallback, desired = host)
                    }
                }
            }
        }
    }

    private fun tryOpen(host: String): ServerSocket? {
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            // Must be set before bind or the size is ignored; after bind it
            // can throw on Android 16 (that was the original swap failure).
            server.receiveBufferSize = TransportBuffering.LISTEN_RECEIVE_BUFFER
            server.bind(InetSocketAddress(host, port), 8)
            lastBindError = null
            server
        } catch (e: Exception) {
            logBindError(host, e)
            null
        }
    }

    private fun installListenLocked(server: ServerSocket?, boundHost: String, desired: String) {
        if (server != null) {
            listen = ListenBind(
                server = server,
                generation = listenGeneration.incrementAndGet(),
                host = boundHost,
            )
            val extra = if (boundHost == ListenerGate.LOOPBACK) " (usb session active)" else ""
            Log.i(WireProtocol.LOG_TAG, "listener bound $boundHost:$port$extra")
            if (boundHost == desired) {
                retryAttempt = 0
                cancelRetryLocked()
            } else {
                scheduleRetryLocked(desired)
            }
        } else {
            listen = null
            scheduleRetryLocked(desired)
        }
        bindChanged.signalAll()
    }

    private fun closeListenSocketLocked(current: ListenBind?) {
        try {
            current?.server?.close()
        } catch (_: Exception) {
        }
    }

    private fun scheduleRetryLocked(desired: String) {
        val exec = scheduler ?: return
        retryFuture?.cancel(false)
        val delay = ListenerSwapPolicy.retryDelayMs(retryAttempt)
        retryAttempt += 1
        retryFuture = exec.schedule({
            if (running.get() && desiredHost == desired) {
                rebind(desired)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelRetryLocked() {
        retryFuture?.cancel(false)
        retryFuture = null
    }

    private fun logBindError(host: String, error: Exception) {
        val key = "${error.javaClass.name}: ${error.message}"
        if (lastBindError == key) return
        lastBindError = key
        Log.w(WireProtocol.LOG_TAG, "listener bind $host:$port failed: $key", error)
    }

    private fun applyActions(actions: List<NewcomerMachine.Action>, initialSocket: Socket? = null) {
        for (action in actions) {
            when (action) {
                is NewcomerMachine.Action.SendHello -> {
                    val socket = sockets[action.connectionId] ?: initialSocket
                    if (socket != null) sendHelloSync(socket)
                }
                is NewcomerMachine.Action.Adopt -> adopt(
                    id = action.connectionId,
                    initialBytes = action.initialBytes,
                    closeOld = action.closeSessionId,
                )
                is NewcomerMachine.Action.Discard -> discard(action.connectionId)
                is NewcomerMachine.Action.ArmTimeout -> armTimeout(action.connectionId, action.timeoutMs)
                is NewcomerMachine.Action.CancelTimeout -> cancelTimeout(action.connectionId)
            }
        }
    }

    private fun sendHelloSync(socket: Socket) {
        try {
            val json = helloProvider(socket.inetAddress)
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            Framer.write(socket.getOutputStream(), payload)
            val port = json.optInt("cursorPort", -1)
            Log.i(
                WireProtocol.LOG_TAG,
                "hello sent ${json.optInt("pixelsWide")}x${json.optInt("pixelsHigh")}" +
                    if (port > 0) " cursorPort=$port" else "",
            )
        } catch (e: Exception) {
            Log.w(WireProtocol.LOG_TAG, "hello send failed: ${e.message}")
        }
    }

    private fun armTimeout(id: Long, timeoutMs: Long) {
        val socket = sockets[id] ?: return
        val exec = scheduler ?: return
        timeouts[id] = exec.schedule({
            parkUntilByte(id, socket, timeoutMs)
        }, 0, TimeUnit.MILLISECONDS)
    }

    private fun parkUntilByte(id: Long, socket: Socket, timeoutMs: Long) {
        try {
            socket.soTimeout = timeoutMs.toInt()
            val buf = ByteArray(1 shl 16)
            val n = socket.getInputStream().read(buf)
            if (n > 0) {
                socket.soTimeout = 0
                val bytes = buf.copyOf(n)
                val actions = synchronized(machineLock) { machine.onBytes(id, bytes) }
                applyActions(actions)
            } else {
                val actions = synchronized(machineLock) { machine.onClosed(id) }
                applyActions(actions)
            }
        } catch (_: SocketTimeoutException) {
            Log.i(WireProtocol.LOG_TAG, "newcomer timed out — keeping live session")
            val actions = synchronized(machineLock) {
                machine.onTimeout(id, SystemClock.elapsedRealtime())
            }
            applyActions(actions)
        } catch (e: IOException) {
            val actions = synchronized(machineLock) { machine.onClosed(id) }
            applyActions(actions)
        }
    }

    private fun cancelTimeout(id: Long) {
        timeouts.remove(id)?.cancel(false)
    }

    private fun discard(id: Long) {
        cancelTimeout(id)
        val socket = sockets.remove(id)
        if (socket != null) {
            Log.i(WireProtocol.LOG_TAG, "discarded newcomer $id")
            closeQuietly(socket)
        }
    }

    private fun adopt(id: Long, initialBytes: ByteArray, closeOld: Long?) {
        if (closeOld != null && closeOld != id) {
            liveConnection?.let {
                Log.i(
                    WireProtocol.LOG_TAG,
                    "newcomer proved itself — adopting it as the session " +
                        "(${initialBytes.size} initial bytes)",
                )
                it.close()
                onSession.onSessionClosed(it.generation)
            }
            liveConnection = null
            sockets.remove(closeOld)?.let { closeQuietly(it) }
        }
        val socket = sockets[id] ?: return
        try {
            socket.soTimeout = 0
        } catch (_: Exception) {
        }
        val connection = FramedConnection(socket, initialBytes)
        val outbound = OutboundQueue()
        val session = LiveSession(
            generation = id,
            connection = connection,
            outbound = outbound,
            transport = connection.transport,
        )
        liveConnection = session
        syncBindToLiveSession()
        session.onFrame = onSession.onAdopted(connection, id, initialBytes, connection.transport)
        session.writer = Thread({ writeLoop(session) }, "od-write").apply {
            isDaemon = true
            start()
        }
        session.reader = Thread({ readLoop(session) }, "od-read").apply {
            isDaemon = true
            start()
        }
        session.watchdog = (scheduler ?: return).scheduleAtFixedRate({
            if (session.connection.idleForMs() > WireProtocol.WATCHDOG_IDLE_MS) {
                Log.i(WireProtocol.LOG_TAG, "watchdog")
                onSession.onWatchdog()
                dropSession(session)
            }
        }, 2, 2, TimeUnit.SECONDS)
    }

    private fun readLoop(session: LiveSession) {
        try {
            while (session.alive.get() && session.connection.isOpen) {
                val payload = session.connection.readFrame()
                val handler = session.onFrame
                    ?: throw IllegalStateException("reader started without a frame handler")
                handler(payload)
            }
        } catch (e: Exception) {
            if (session.alive.get()) {
                Log.i(WireProtocol.LOG_TAG, "session read ended: ${e.message}")
            }
        } finally {
            dropSession(session)
        }
    }

    private fun writeLoop(session: LiveSession) {
        try {
            while (session.alive.get()) {
                val message = session.outbound.take() ?: break
                session.connection.writeFrame(message.payload)
            }
        } catch (e: Exception) {
            if (session.alive.get()) {
                Log.i(WireProtocol.LOG_TAG, "session write ended: ${e.message}")
            }
        }
    }

    private fun dropSession(session: LiveSession) {
        if (!session.alive.compareAndSet(true, false)) return
        session.watchdog?.cancel(false)
        session.outbound.close()
        session.connection.close()
        sockets.remove(session.generation)
        if (liveConnection === session) {
            liveConnection = null
            val actions = synchronized(machineLock) { machine.onClosed(session.generation) }
            applyActions(actions)
            onSession.onSessionClosed(session.generation)
            syncBindToLiveSession()
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    class LiveSession(
        val generation: Long,
        val connection: FramedConnection,
        val outbound: OutboundQueue,
        val transport: String,
    ) {
        val alive = AtomicBoolean(true)
        var reader: Thread? = null
        var writer: Thread? = null
        var watchdog: ScheduledFuture<*>? = null
        var onFrame: ((ByteArray) -> Unit)? = null

        fun close() {
            alive.set(false)
            watchdog?.cancel(false)
            outbound.close()
            connection.close()
        }
    }

    private data class ListenBind(
        val server: ServerSocket,
        val generation: Long,
        val host: String,
    )
}
