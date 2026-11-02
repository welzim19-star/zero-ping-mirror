package com.zeroping.mirror.core.network

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.zeroping.mirror.core.obs.KillcamLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.*

class WebRTCPipeline(
    private val context: Context,
    private val signalingServer: SignalingServer
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var rootEglBase: EglBase? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoTrack: VideoTrack? = null

    init {
        initWebRTC()
        listenToSignaling()
    }

    private fun initWebRTC() {
        try {
            rootEglBase = EglBase.create()
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
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
            val videoSource = peerConnectionFactory?.createVideoSource((videoCapturer as ScreenCapturerAndroid).isScreencast)
            
            videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            // 720p 60fps
            videoCapturer?.startCapture(1280, 720, 60)

            localVideoTrack = peerConnectionFactory?.createVideoTrack("ARDAMSv0", videoSource)
            setupPeerConnection()
        } catch (e: Exception) {
            KillcamLogger.logError("WebRTC-Capture", e)
        }
    }

    private fun setupPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList())
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                KillcamLogger.logInfo("WebRTC", "ICE State mudou para: ${newState?.name}")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let {
                    val json = JSONObject()
                    json.put("type", "candidate")
                    val candJson = JSONObject()
                    candJson.put("sdpMLineIndex", it.sdpMLineIndex)
                    candJson.put("sdpMid", it.sdpMid)
                    candJson.put("candidate", it.sdp)
                    json.put("candidate", candJson)
                    CoroutineScope(Dispatchers.IO).launch {
                        signalingServer.sendMessage(json.toString())
                    }
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        localVideoTrack?.let {
            peerConnection?.addTrack(it, listOf("ARDAMS"))
        }

        // Criar Offer para o player que conectou no lobby
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                peerConnection?.setLocalDescription(SimpleSdpObserver(), sessionDescription)
                sessionDescription?.let {
                    val json = JSONObject()
                    json.put("type", "offer")
                    val offerJson = JSONObject()
                    offerJson.put("type", "offer")
                    offerJson.put("sdp", it.description)
                    json.put("offer", offerJson)
                    CoroutineScope(Dispatchers.IO).launch {
                        signalingServer.sendMessage(json.toString())
                    }
                    KillcamLogger.logInfo("WebRTC", "Offer enviada pro lobby")
                }
            }
        }, constraints)
    }

    private fun listenToSignaling() {
        CoroutineScope(Dispatchers.IO).launch {
            signalingServer.incomingMessages.collect { message ->
                try {
                    val json = JSONObject(message)
                    val type = json.getString("type")
                    if (type == "answer") {
                        val answerJson = json.getJSONObject("answer")
                        val sdp = answerJson.getString("sdp")
                        val sessionDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), sessionDesc)
                        KillcamLogger.logInfo("WebRTC", "Answer recebida (Conexão p2p travada).")
                    } else if (type == "candidate") {
                        val candJson = json.getJSONObject("candidate")
                        val iceCandidate = IceCandidate(
                            candJson.getString("sdpMid"),
                            candJson.getInt("sdpMLineIndex"),
                            candJson.getString("candidate")
                        )
                        peerConnection?.addIceCandidate(iceCandidate)
                    }
                } catch (e: Exception) {
                    KillcamLogger.logError("WebRTC-Signaling", e)
                }
            }
        }
    }

    open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(s: String?) {
            KillcamLogger.logError("WebRTC-SDP", Exception(s))
        }
        override fun onSetFailure(s: String?) {
            KillcamLogger.logError("WebRTC-SDP-Set", Exception(s))
        }
    }
}
