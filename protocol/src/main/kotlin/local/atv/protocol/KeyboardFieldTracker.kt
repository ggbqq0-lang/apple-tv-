package local.atv.protocol

/** Track the current field only; no draft or history is retained per field. */
class KeyboardFieldTracker {
    private var observed=false
    private var session:ByteArray?=null
    data class Change(val focused:Boolean,val text:String) {
        override fun toString()="KeyboardFieldChange(focused=$focused, [redacted])"
    }
    fun reset(){observed=false;session=null}
    fun observe(state:TextState):Change? {
        val current=state.sessionId?.takeIf{state.focused && it.size==16}
        if(observed && same(session,current))return null
        observed=true;session=current?.copyOf()
        return Change(current!=null,if(current!=null)state.context.orEmpty() else "")
    }
    /** Capture a fixed target so a queued edit can never follow a later field. */
    fun snapshot():LiveTextPolicy?=session?.let{LiveTextPolicy().apply{arm(it)}}
    private fun same(first:ByteArray?,second:ByteArray?)=if(first==null)second==null else first.contentEquals(second)
}
