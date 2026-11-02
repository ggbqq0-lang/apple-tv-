package local.atv.protocol

/** A temporary TV connection for one deliberate mute click; never retries. */
class LinkedTvMute(private val factory:(Endpoint)->RemoteSession={MiLink(it)}) {
    @Volatile private var generation=0L
    private var active:RemoteSession?=null

    fun toggle(endpoint:Endpoint,current:()->Boolean):Boolean {
        val token=generation
        if(!current())return false
        val client=factory(endpoint)
        try {
            synchronized(this){
                if(token!=generation || !current())return false
                check(active==null)
                active=client
            }
            client.connect()
            if(token!=generation || !current())return false
            client.toggleMute()
            return token==generation && current()
        } finally {
            synchronized(this){if(active===client)active=null}
            runCatching{client.close()}
        }
    }

    fun cancel(){
        val client=synchronized(this){generation++;active.also{active=null}}
        // Closing interrupts blocked connect/read without holding this monitor.
        runCatching{client?.close()}
    }
}
