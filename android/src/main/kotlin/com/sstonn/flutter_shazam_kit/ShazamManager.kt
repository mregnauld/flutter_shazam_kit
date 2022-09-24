package com.sstonn.flutter_shazam_kit

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.shazam.shazamkit.*
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ShazamManager(private val callbackChannel: MethodChannel) {
    private lateinit var catalog: Catalog
    private lateinit var currentSession: StreamingSession
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main)


    fun configureShazamKitSession(
        developerToken: String?,
        flutterResult: MethodChannel.Result
    ) {
        if (developerToken == null) {
            flutterResult.success(null)
            return
        }
        val tokenProvider = DeveloperTokenProvider {
            DeveloperToken(developerToken)
        }
        catalog = ShazamKit.createShazamCatalog(tokenProvider)
        coroutineScope.launch {
            when (val result = ShazamKit.createStreamingSession(
                catalog,
                AudioSampleRateInHz.SAMPLE_RATE_44100,
                8192
            )) {
                is ShazamKitResult.Success -> {
                    currentSession = result.data

                }
                is ShazamKitResult.Failure -> {
                    result.reason.message?.let { onError(it) }
                }
            }
            flutterResult.success(null)
            currentSession.recognitionResults().collect { result: MatchResult ->
                if (result is MatchResult.Match) {
                    callbackChannel.invokeMethod("mediaItemsFound", result.toJsonString())
                }
            }

        }

    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        callbackChannel.invokeMethod("detectStateChanged", 1)
        val audioSource = MediaRecorder.AudioSource.DEFAULT
        val audioFormat = AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(41_000).build()

        audioRecord =
            AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
                .build()
        val bufferSize = AudioRecord.getMinBufferSize(
            41_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord?.startRecording()
        isRecording = true
        recordingThread = Thread({
            val readBuffer = ByteArray(bufferSize)
            while (isRecording) {
                val actualRead = audioRecord!!.read(readBuffer, 0, bufferSize)
                currentSession.matchStream(readBuffer, actualRead, System.currentTimeMillis())
            }
        }, "AudioRecorder Thread")
        recordingThread!!.start()
    }

    fun stopListening() {
        callbackChannel.invokeMethod("detectStateChanged", 0)
        if (audioRecord != null) {
            isRecording = false;
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordingThread = null
        }
    }

    private fun onError(message: String) {
        callbackChannel.invokeMethod("didHasError", message)
    }
}

fun MatchResult.Match.toJsonString(): String {
    val itemJsonArray = JSONArray()
    this.matchedMediaItems.forEach { item ->
        val itemJsonObject = JSONObject()
        itemJsonObject.put("title", item.title)
        itemJsonObject.put("subtitle", item.subtitle)
        itemJsonObject.put("shazamId", item.shazamID)
        itemJsonObject.put("appleMusicId", item.appleMusicID)
        item.appleMusicURL?.let {
            itemJsonObject.put("appleMusicUrl", it.toURI().toString())
        }
        item.artworkURL?.let {
            itemJsonObject.put("artworkUrl", it.toURI().toString())
        }
        itemJsonObject.put("artist", item.artist)
        itemJsonObject.put("matchOffset", item.matchOffsetInMs)
//        item.videoURL?.let {
//            itemJsonObject.put("videoUrl", it.content.toString())
//        }
//        item.webURL?.let {
//            itemJsonObject.put("webUrl", it.content.toString())
//        }
        itemJsonObject.put("genres", JSONArray(item.genres))
        itemJsonObject.put("isrc", item.isrc)
        itemJsonArray.put(itemJsonObject)
    }
    return itemJsonArray.toString()
}