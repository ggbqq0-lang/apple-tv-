package local.atv.protocol

/** Absolute-volume mute is separate from relative TV volume keys. */
class NetworkMute {
    private var restore:Double?=null
    private var rejected=false
    val muted get()=restore!=null
    fun available(level:Double?)=!rejected && level!=null && (restore!=null || level>0.0)
    fun observe(level:Double?){if(level==null || level>0.0)restore=null}
    fun reset(){restore=null;rejected=false}
    fun toggle(read:()->Double,writeVerified:(Double)->Unit):Boolean {
        val before=read()
        val previous=restore
        // A zero reading may mean "unknown" for HDMI. It must never become
        // an implicit unmute or a guessed volume-up command.
        ensure(previous!=null || before>0.0,"MUTE_UNAVAILABLE")
        val target=previous ?: 0.0
        try{writeVerified(target)}catch(e:ProtocolFailure){if(e.code=="MUTE_UNAVAILABLE")rejected=true;throw e}
        restore=if(previous==null)before else null
        return muted
    }
}
