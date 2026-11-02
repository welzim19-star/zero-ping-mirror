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

class SignalingServer(private val port: Int = 8080) {
    private var engine: NettyApplicationEngine? = null
    
    private val _incomingMessages = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingMessages = _incomingMessages.asSharedFlow()
    
    private var activeSession: DefaultWebSocketServerSession? = null

    fun start() {
        try {
            engine = embeddedServer(Netty, port = port) {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                
                routing {
                    get("/") {
                        call.respondText(
                            getWebClientHtml(),
                            ContentType.Text.Html
                        )
                    }
                    
                    webSocket("/signaling") {
                        KillcamLogger.logInfo("SignalingServer", "Player conectou no lobby (Websocket)")
                        activeSession = this
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val message = frame.readText()
                                    _incomingMessages.tryEmit(message)
                                }
                            }
                        } catch (e: Exception) {
                            KillcamLogger.logError("SignalingServer-WS", e)
                        } finally {
                            KillcamLogger.logWarning("SignalingServer", "Player quitou da partida.")
                            activeSession = null
                        }
                    }
                }
            }.start(wait = false)
            KillcamLogger.logInfo("SignalingServer", "Host spawnou o server na porta $port")
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
        engine?.stop(1000, 2000)
        KillcamLogger.logInfo("SignalingServer", "Host derrubou o server")
    }

    private fun getWebClientHtml(): String {
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
                <video id="stream" autoplay playsinline></video>
                <script>
                    const video = document.getElementById('stream');
                    const status = document.getElementById('status');
                    const ws = new WebSocket('ws://' + location.host + '/signaling');
                    const pc = new RTCPeerConnection({ iceServers: [] });
                    
                    pc.ontrack = (event) => {
                        console.log('Stream dropado (recebido)! GGWP.');
                        status.innerText = 'Status: In-Game (Stream Ativo)';
                        video.srcObject = event.streams[0];
                    };
                    
                    pc.onicecandidate = (event) => {
                        if (event.candidate) {
                            ws.send(JSON.stringify({ type: 'candidate', candidate: event.candidate }));
                        }
                    };
                    
                    ws.onopen = () => { status.innerText = 'Status: Conectado ao Websocket (Aguardando stream...)'; };
                    ws.onclose = () => { status.innerText = 'Status: Disconnect (Host caiu)'; };
                    
                    ws.onmessage = async (event) => {
                        const msg = JSON.parse(event.data);
                        if (msg.type === 'offer') {
                            status.innerText = 'Status: Negociando conexão (Offer recebida)...';
                            await pc.setRemoteDescription(new RTCSessionDescription(msg.offer));
                            const answer = await pc.createAnswer();
                            await pc.setLocalDescription(answer);
                            ws.send(JSON.stringify({ type: 'answer', answer: answer }));
                        } else if (msg.type === 'candidate') {
                            await pc.addIceCandidate(new RTCIceCandidate(msg.candidate));
                        }
                    };
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
