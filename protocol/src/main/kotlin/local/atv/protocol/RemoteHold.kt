package local.atv.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A physical hold ends on finger-up/cancellation, with a hard safety deadline. */
class RemoteHold(timeoutMillis:Long=15000) {
    private val released=CountDownLatch(1)
    private val deadline=System.nanoTime()+timeoutMillis*1000000
    init{require(timeoutMillis in 1..15000)}
    fun active()=released.count>0 && System.nanoTime()<deadline
    fun release(){released.countDown()}
    fun awaitRelease(){
        try{released.await((deadline-System.nanoTime()).coerceAtLeast(0),TimeUnit.NANOSECONDS)}finally{release()}
    }
}
