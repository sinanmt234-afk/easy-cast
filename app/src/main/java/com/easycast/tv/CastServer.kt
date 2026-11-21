package com.easycast.tv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** HTTPS page + secure WebSocket signaling, all hosted on the TV. */
class CastServer(private val ctx: Context, port: Int, private val onMsg: (String) -> Unit) : NanoWSD(port) {
    @Volatile private var socket: WebSocket? = null
    private val out = Executors.newSingleThreadExecutor()

    init {
        val pass = "easycast".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("ec.p12").use { ks.load(it, pass) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, pass)
        val sc = SSLContext.getInstance("TLS")
        sc.init(kmf.keyManagers, null, null)
        makeSecure(sc.serverSocketFactory, null)
    }

    fun send(s: String) {
        out.execute { try { socket?.send(s) } catch (_: Exception) {} }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = object : WebSocket(handshake) {
        override fun onOpen() {
            socket?.let { try { it.close(WebSocketFrame.CloseCode.NormalClosure, "replaced", false) } catch (_: Exception) {} }
            socket = this
        }
        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, remote: Boolean) {
            if (socket === this) { socket = null; onMsg("""{"type":"bye"}""") }
        }
        override fun onMessage(message: WebSocketFrame) { onMsg(message.textPayload) }
        override fun onPong(pong: WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val html = ctx.assets.open("index.html").readBytes().toString(Charsets.UTF_8)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }
}
