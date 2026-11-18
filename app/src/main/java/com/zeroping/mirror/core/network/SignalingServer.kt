package com.zeroping.mirror.core.network

import com.zeroping.mirror.core.obs.KillcamLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration
import java.util.UUID

class SignalingServer(private val port: Int = 8080) {
    private var engine: NettyApplicationEngine? = null
    val sessionToken: String = UUID.randomUUID().toString().substring(0, 8)
    
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()
    
    @Volatile
    private var activeSession: DefaultWebSocketServerSession? = null

    fun start() {
        if (engine != null) return
        try {
            engine = embeddedServer(Netty, port = port) {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = 1024 * 1024L
                    masking = false
                }
                
                routing {
                    get("/") {
                        val token = call.request.queryParameters["token"]
                        if (token != sessionToken) {
                            call.respond(HttpStatusCode.Unauthorized, "401 Unauthorized - Token invalido")
                            return@get
                        }
                        call.respondText(getWebClientHtml(sessionToken), ContentType.Text.Html)
                    }
                    
                    webSocket("/signaling") {
                        val token = call.request.queryParameters["token"]
                        if (token != sessionToken) {
                            KillcamLogger.logWarning("SignalingServer", "Conexao bloqueada (Token invalido).")
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                            return@webSocket
                        }

                        KillcamLogger.logInfo("SignalingServer", "Player autenticado no lobby (Websocket)")
                        activeSession?.close(CloseReason(CloseReason.Codes.NORMAL, "New session replaced"))
                        activeSession = this
                        _incomingMessages.tryEmit("""{"type":"client_ready"}""")

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    _incomingMessages.tryEmit(frame.readText())
                                }
                            }
                        } catch (e: Exception) {
                            KillcamLogger.logError("SignalingServer-WS", e)
                        } finally {
                            KillcamLogger.logWarning("SignalingServer", "Player quitou da partida.")
                            if (activeSession == this) activeSession = null
                        }
                    }
                }
            }.start(wait = false)
            KillcamLogger.logInfo("SignalingServer", "Host spawnou server na porta $port [Token: $sessionToken]")
        } catch (e: Exception) {
            KillcamLogger.logError("SignalingServer-Start", e)
        }
    }
    suspend fun sendMessage(message: String) {
        try {
            activeSession?.send(Frame.Text(message))
        } catch (e: Exception) {
            KillcamLogger.logError("SignalingServer-Send", e)
        }
    }

    fun stop() {
        try {
            engine?.stop(200, 500)
            engine = null
            activeSession = null
            KillcamLogger.logInfo("SignalingServer", "Host derrubou o server")
        } catch (e: Exception) {
            KillcamLogger.logError("SignalingServer-Stop", e)
        }
    }

    private fun getWebClientHtml(token: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Zero-Ping Mirror</title>
                <style>
                    body { margin: 0; background: #0a0a0c; display: flex; justify-content: center; align-items: center; height: 100vh; overflow: hidden; }
                    video { width: 100vw; height: 100vh; object-fit: contain; }
                    #status { position: absolute; top: 20px; left: 20px; color: #00ff00; font-family: monospace; font-size: 14px; text-shadow: 0 0 5px #00ff00; }
                </style>
            </head>
            <body>
                <div id="status">Status: Conectando no lobby...</div>
                <video id="stream" autoplay playsinline muted></video>
                <script>
                    const video = document.getElementById('stream');
                    const status = document.getElementById('status');
                    const ws = new WebSocket('ws://' + location.host + '/signaling?token=$token');
                    let pc = null;

                    function createPeer() {
                        if (pc) pc.close();
                        pc = new RTCPeerConnection({ iceServers: [] });
                        pc.ontrack = (event) => {
                            if (event.receiver && 'playoutDelayHint' in event.receiver) {
                                event.receiver.playoutDelayHint = 0;
                            }
                            status.innerText = 'Status: In-Game (Stream Ativo)';
                            video.srcObject = event.streams[0];
                        };
                        pc.onicecandidate = (event) => {
                            if (event.candidate) {
                                ws.send(JSON.stringify({ type: 'candidate', candidate: event.candidate }));
                            }
                        };
                    }
                    
                    ws.onopen = () => { status.innerText = 'Status: Conectado ao Websocket (Solicitando stream...)'; };
                    ws.onclose = () => { status.innerText = 'Status: Disconnect (Host caiu ou token expirado)'; };
                    
                    ws.onmessage = async (event) => {
                        const msg = JSON.parse(event.data);
                        if (msg.type === 'offer') {
                            createPeer();
                            status.innerText = 'Status: Negociando conexao (Offer recebida)...';
                            await pc.setRemoteDescription(new RTCSessionDescription(msg.offer));
                            const answer = await pc.createAnswer();
                            await pc.setLocalDescription(answer);
                            ws.send(JSON.stringify({ type: 'answer', answer: answer }));
                        } else if (msg.type === 'candidate' && pc) {
                            await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
