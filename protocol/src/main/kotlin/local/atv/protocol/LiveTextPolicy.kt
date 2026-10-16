package local.atv.protocol

/** Live edits are authorized for one explicit RTI target; never silently retarget. */
class LiveTextPolicy {
    @Volatile private var target: ByteArray?=null
    private var generation=0L
    private var pending=false
    @Synchronized fun requestBinding():Long {target=null;pending=true;return ++generation}
    @Synchronized fun bind(request:Long,session:ByteArray?):Boolean {
        if(request!=generation || !pending || session?.size!=16)return false
        target=session.copyOf();pending=false;return true
    }
    @Synchronized fun arm(session: ByteArray?){ensure(session?.size==16,"NO_FOCUSED_TEXT_FIELD");generation++;pending=false;target=session!!.copyOf()}
    fun check(session: ByteArray?){ensure(target!=null && session?.contentEquals(target)==true,"LIVE_TARGET_CHANGED")}
    @Synchronized fun stop(){generation++;pending=false;target=null}
    fun active()=target!=null
    /** Keep the authorized target while stopping the debounced live worker. */
    @Synchronized fun snapshot():LiveTextPolicy?=target?.let{session->LiveTextPolicy().apply{arm(session)}}
    companion object {
        // contextBeforeInput is not guaranteed to be a complete document. Never
        // import it as the full editable value, or restore a stale local draft.
        fun draftForEntry(local:String,contextBeforeInput:String?):String=if(local==contextBeforeInput)local else ""
    }
}
