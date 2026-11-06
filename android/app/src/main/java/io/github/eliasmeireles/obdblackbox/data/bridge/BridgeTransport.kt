package io.github.eliasmeireles.obdblackbox.data.bridge

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** One open link to the bridge. Blocking calls: use them from an IO dispatcher. */
interface BridgeConnection : AutoCloseable {
    /** Next line, or null when the bridge closed the link. Throws on read timeout. */
    fun readLine(): String?

    fun send(line: String)
}

/** In-process bridges persist the before-clear snapshot before issuing mode 04. */
interface BeforeClearPersistence {
    var saveBeforeClear: ((String) -> Unit)?
}

fun interface BridgeTransport {
    fun connect(): BridgeConnection
}

/** Development transport: the fake bridge on the PC through `adb reverse tcp:35000 tcp:35000`. */
class TcpBridgeTransport(
    private val host: String,
    private val port: Int,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : BridgeTransport {

    override fun connect(): BridgeConnection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs
            return StreamConnection(socket.getInputStream().bufferedReader(), socket.getOutputStream(), socket)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
    }
}

/** Line-oriented connection over any input/output stream pair (TCP now, Bluetooth SPP later). */
class StreamConnection(
    private val reader: BufferedReader,
    private val output: OutputStream,
    private val closeable: AutoCloseable,
) : BridgeConnection {
    constructor(input: java.io.InputStream, output: OutputStream, closeable: AutoCloseable) :
        this(BufferedReader(InputStreamReader(input, Charsets.UTF_8)), output, closeable)

    override fun readLine(): String? = reader.readLine()

    @Synchronized
    override fun send(line: String) {
        output.write("$line\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    override fun close() = closeable.close()
}
