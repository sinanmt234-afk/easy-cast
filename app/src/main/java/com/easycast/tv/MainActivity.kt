package com.easycast.tv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

private val Gold = Color(0xFFD4AF37)
private val Ivory = Color(0xFFEDE6D6)
private val Muted = Color(0xFF8C8570)

class MainActivity : ComponentActivity() {
    private val egl = EglBase.create()
    private lateinit var receiver: Receiver
    private var server: CastServer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var track by mutableStateOf<VideoTrack?>(null)
    private var mode by mutableStateOf("")
    private var toast by mutableStateOf("")
    private var ip by mutableStateOf("")
    private var error by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        receiver = Receiver(applicationContext, egl,
            send = { server?.send(it) },
            onVideo = { t -> ui.post { track = t } },
            onMode = { m -> ui.post { mode = m } },
            onEnd = { ui.post { track = null; mode = "" } })
        ip = localIp()
        try {
            server = CastServer(this, 8443) { msg -> ui.post { receiver.handle(msg) } }.also { it.start(0, false) }
        } catch (e: Exception) { error = "Could not start: ${e.message}" }
        advertise()
        setContent { Root() }
    }

    private var nsdListener: NsdManager.RegistrationListener? = null

    /** Lets the Easy Cast phone app find this TV automatically. */
    private fun advertise() {
        try {
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo?, c: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo?, c: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo?) {}
                override fun onServiceUnregistered(s: NsdServiceInfo?) {}
            }
            val info = NsdServiceInfo().apply { serviceName = "Easy Cast TV"; serviceType = "_easycast._tcp."; port = 8443 }
            (getSystemService(NSD_SERVICE) as NsdManager).registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsdListener = l
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        nsdListener?.let { try { (getSystemService(NSD_SERVICE) as NsdManager).unregisterService(it) } catch (_: Exception) {} }
        server?.stop(); super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (track == null) return super.onKeyDown(keyCode, event)
        fun cmd(json: String, label: String) {
            if (mode == "video") { receiver.ctrl(json); toast = label }
            else toast = "Remote control works in Cast Video mode"
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> cmd("""{"cmd":"toggle"}""", "Play / Pause")
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> cmd("""{"cmd":"seek","delta":-10}""", "Back 10 s")
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> cmd("""{"cmd":"seek","delta":10}""", "Forward 10 s")
            KeyEvent.KEYCODE_BACK -> { server?.send("""{"type":"bye"}"""); receiver.close() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun localIp(): String = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { Collections.list(it.inetAddresses) }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "No network"
    } catch (_: Exception) { "No network" }

    @Composable
    private fun Root() {
        val t = track
        val view = remember {
            SurfaceViewRenderer(this@MainActivity).apply {
                init(egl.eglBaseContext, null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }
        DisposableEffect(t) { t?.addSink(view); onDispose { t?.removeSink(view) } }
        LaunchedEffect(toast) { if (toast.isNotEmpty()) { delay(2000); toast = "" } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (t == null) Idle() else {
                AndroidView({ view }, Modifier.fillMaxSize())
                if (toast.isNotEmpty()) Box(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xCC000000))
                        .border(1.dp, Gold, RoundedCornerShape(50)).padding(horizontal = 28.dp, vertical = 12.dp)
                ) { BasicText(toast, style = TextStyle(color = Ivory, fontSize = 20.sp)) }
            }
        }
    }

    @Composable
    private fun Idle() {
        Column(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF241C08), Color.Black))),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText("EASY CAST", style = TextStyle(color = Gold, fontSize = 64.sp, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Light, letterSpacing = 16.sp))
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(120.dp).height(1.dp).background(Gold))
            Spacer(Modifier.height(18.dp))
            BasicText("Cast your screen in full quality", style = TextStyle(color = Ivory, fontSize = 22.sp,
                fontFamily = FontFamily.Serif, letterSpacing = 3.sp))
            Spacer(Modifier.height(44.dp))
            Box(Modifier.border(1.dp, Gold, RoundedCornerShape(50)).padding(horizontal = 40.dp, vertical = 16.dp)) {
                BasicText(if (error.isEmpty()) "https://$ip:8443" else error,
                    style = TextStyle(color = Gold, fontSize = 30.sp, letterSpacing = 2.sp))
            }
            Spacer(Modifier.height(36.dp))
            BasicText("1   Open this address in a browser on your computer or phone",
                style = TextStyle(color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center))
            Spacer(Modifier.height(8.dp))
            BasicText("2   Accept the security notice once, then choose Mirror Screen or Cast Video",
                style = TextStyle(color = Muted, fontSize = 18.sp, textAlign = TextAlign.Center))
        }
    }
}
