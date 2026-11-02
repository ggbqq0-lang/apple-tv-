package local.atv.protocol

import java.io.Closeable

/** Selected remote connection; an explicit TV linkage may handle mute only. */
interface RemoteSession:Closeable {
    val focused:Boolean
    val supportsText:Boolean get()=true
    val supportsPower:Boolean get()=true
    val supportsMuteToggle:Boolean get()=false
    val supportsVolume:Boolean
    val supportsVolumeSteps:Boolean
    fun connect()
    fun refreshText():TextState
    fun sendText(text:String,append:Boolean,confirmReplace:Boolean,livePolicy:LiveTextPolicy?=null):SendResult = throw ProtocolFailure("TEXT_UNSUPPORTED")
    fun press(command:Int,hold:Boolean=false)
    fun holdSelect(held:RemoteHold)
    fun power(wake:Boolean):Unit=throw ProtocolFailure("POWER_UNSUPPORTED")
    fun volume():Double=throw ProtocolFailure("AUDIO_UNSUPPORTED")
    fun setVolumeVerified(level:Double):Unit=throw ProtocolFailure("MUTE_UNAVAILABLE")
    fun toggleMute():Unit=throw ProtocolFailure("MUTE_UNAVAILABLE")
}
