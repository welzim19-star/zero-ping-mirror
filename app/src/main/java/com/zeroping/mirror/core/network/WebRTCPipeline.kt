package com.zeroping.mirror.core.network

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.zeroping.mirror.core.obs.KillcamLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.atomic.AtomicBoolean

class WebRTCPipeline(
    private val context: Context,
    private val signalingServer: SignalingServer
) {
    companion object {
        private val webrtcInitialized = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null

    init {
        initWebRTC()
        listenToSignaling()
    }

    private fun initWebRTC() {
        try {
            rootEglBase = EglBase.create()
            if (webrtcInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
            val options = PeerConnectionFactory.Options()
            val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(
                rootEglBase?.eglBaseContext, true, true
            )
            val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(defaultVideoEncoderFactory)
                .setVideoDecoderFactory(defaultVideoDecoderFactory)
                .createPeerConnectionFactory()
                
            KillcamLogger.logInfo("WebRTC", "Engine carregada. GG!")
        } catch (e: Exception) {
            KillcamLogger.logError("WebRTC-Init", e)
        }
    }

    fun startCapture(mediaProjectionIntent: Intent) {
        try {
            videoCapturer = ScreenCapturerAndroid(mediaProjectionIntent, object : MediaProjection.Callback() {
                override fun onStop() {
                    KillcamLogger.logWarning("WebRTC", "MediaProjection parou de stremar.")
                }
            })

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
            videoSource = peerConnectionFactory?.createVideoSource(true)
            
            videoCapturer?.initialize(surfaceTextureHelper, context.applicationContext, videoSource?.capturerObserver)
            videoCapturer?.startCapture(1280, 720, 60)

            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            KillcamLogger.logInfo("WebRTC", "Captura 720p60 pronta. Aguardando player no lobby...")
        } catch (e: Exception) {
            KillcamLogger.logError("WebRTC-Capture", e)
        }
    }
    private fun createOfferForClient() {
        try {
            peerConnection?.dispose()
            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    KillcamLogger.logInfo("WebRTC", "ICE State: ${newState?.name}")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        val json = JSONObject().apply {
                            put("type", "candidate")
                            put("candidate", JSONObject().apply {
                                put("sdpMLineIndex", it.sdpMLineIndex)
                                put("sdpMid", it.sdpMid)
                                put("candidate", it.sdp)
                            })
                        }
                        scope.launch { signalingServer.sendMessage(json.toString()) }
                    }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {}
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dataChannel: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            })

            localVideoTrack?.let { track ->
                val sender = peerConnection?.addTrack(track, listOf("ARDAMS"))
                sender?.parameters?.let { params ->
                    params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
                    sender.parameters = params
                }
            }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            peerConnection?.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                    sessionDescription?.let {
                        val json = JSONObject().apply {
                            put("type", "offer")
                            put("offer", JSONObject().apply {
                                put("type", "offer")
                                put("sdp", it.description)
                            })
                        }
                        scope.launch { signalingServer.sendMessage(json.toString()) }
                        KillcamLogger.logInfo("WebRTC", "Offer enviada pro player conectado!")
                    }
                }
            }, constraints)
        } catch (e: Exception) {
            KillcamLogger.logError("WebRTC-Offer", e)
        }
    }

    private fun listenToSignaling() {
        scope.launch {
            signalingServer.incomingMessages.collect { message ->
                try {
                    val json = JSONObject(message)
                    when (json.getString("type")) {
                        "client_ready" -> createOfferForClient()
                        "answer" -> {
                            val sdp = json.getJSONObject("answer").getString("sdp")
                            peerConnection?.setRemoteDescription(
                                SimpleSdpObserver(),
                                SessionDescription(SessionDescription.Type.ANSWER, sdp)
                            )
                            KillcamLogger.logInfo("WebRTC", "Answer recebida (Conexao P2P travada).")
                        }
                        "candidate" -> {
                            val candJson = json.getJSONObject("candidate")
                            peerConnection?.addIceCandidate(
                                IceCandidate(
                                    candJson.getString("sdpMid"),
                                    candJson.getInt("sdpMLineIndex"),
                                    candJson.getString("candidate")
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    KillcamLogger.logError("WebRTC-Signaling", e)
                }
            }
        }
    }

    fun stop() {
        try {
            scope.cancel()
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            localVideoTrack?.dispose()
            videoSource?.dispose()
            surfaceTextureHelper?.dispose()
            peerConnection?.dispose()
            peerConnectionFactory?.dispose()
            rootEglBase?.release()
            KillcamLogger.logInfo("WebRTC", "Pipeline encerrado e memoria liberada.")
        } catch (e: Exception) {
            KillcamLogger.logError("WebRTC-Stop", e)
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) { KillcamLogger.logError("WebRTC-SDP", Exception(s)) }
        override fun onSetFailure(s: String?) { KillcamLogger.logError("WebRTC-SDP-Set", Exception(s)) }
    }
}
