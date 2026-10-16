package local.atv.protocol

/** Flush one committed edit before changing TV focus. A failed flush is never replayed. */
class KeyboardNavigation(private val text:String?,private val policy:LiveTextPolicy?) {
    @Volatile private var cancelled=false
    private var prepared=false
    private var failed=false
    fun cancel(){cancelled=true}
    // Call under the same connection lock as live text and remote commands.
    fun run(current:()->Boolean,send:(String,LiveTextPolicy)->SendResult,action:()->Unit){
        if(cancelled || !current())return
        if(failed)throw ProtocolFailure("KEYBOARD_SYNC_REVIEW_REQUIRED")
        if(!prepared){
            prepared=true
            if(text!=null){
                try{
                    val target=policy ?: throw ProtocolFailure("LIVE_TARGET_CHANGED")
                    if(send(text,target).contextMatches!=true)throw ProtocolFailure("KEYBOARD_SYNC_REVIEW_REQUIRED")
                }catch(e:Exception){failed=true;throw e}
            }
        }
        if(!cancelled && current())action()
    }
}
