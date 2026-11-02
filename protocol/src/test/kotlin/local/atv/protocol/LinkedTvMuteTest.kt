package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class LinkedTvMuteTest {
    private val endpoint=Endpoint("TV","127.0.0.1",6091)
    private class Session(val onConnect:()->Unit={},val onMute:()->Unit={}):RemoteSession {
        override val focused=false
        override val supportsVolume=false
        override val supportsVolumeSteps=false
        var connects=0;var toggles=0;@Volatile var closed=false
        override fun connect(){connects++;onConnect()}
        override fun toggleMute(){check(!closed);toggles++;onMute()}
        override fun close(){closed=true}
        override fun refreshText():TextState=error("must not poll linked TV")
        override fun press(command:Int,hold:Boolean)=error("must not send direction or volume keys")
        override fun holdSelect(held:RemoteHold)=error("must not hold linked TV keys")
    }
    @Test fun twoClicksEachUseOneTemporaryConnectionAndOneToggle(){
        val sessions=mutableListOf<Session>();val mute=LinkedTvMute{Session().also{sessions+=it}}
        repeat(2){assertTrue(mute.toggle(endpoint){true})}
        assertEquals(2,sessions.size)
        sessions.forEach{assertEquals(1,it.connects);assertEquals(1,it.toggles);assertTrue(it.closed)}
    }
    @Test fun stalePrimaryNeverCreatesTvConnection(){
        var created=0;val mute=LinkedTvMute{created++;Session()}
        assertFalse(mute.toggle(endpoint){false});assertEquals(0,created)
    }
    @Test fun changedPrimaryDuringConnectNeverToggles(){
        var current=true;val client=Session(onConnect={current=false})
        assertFalse(LinkedTvMute{client}.toggle(endpoint){current})
        assertEquals(0,client.toggles);assertTrue(client.closed)
    }
    @Test fun connectionFailureClosesWithoutRetryOrMute(){
        val client=Session(onConnect={throw ProtocolFailure("REQUEST_TIMEOUT")})
        assertThrows(ProtocolFailure::class.java){LinkedTvMute{client}.toggle(endpoint){true}}
        assertEquals(1,client.connects);assertEquals(0,client.toggles);assertTrue(client.closed)
    }
    @Test fun uncertainToggleClosesWithoutRetry(){
        val client=Session(onMute={throw ProtocolFailure("XIAOMI_COMMAND_UNCONFIRMED")})
        assertThrows(ProtocolFailure::class.java){LinkedTvMute{client}.toggle(endpoint){true}}
        assertEquals(1,client.toggles);assertTrue(client.closed)
    }
    @Test fun cancelInterruptsBlockedConnectAndPreventsLateMute(){
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val client=Session(onConnect={entered.countDown();check(release.await(2,TimeUnit.SECONDS))})
        val mute=LinkedTvMute{client};val result=AtomicReference<Boolean>();val failure=AtomicReference<Throwable>()
        val worker=thread {try{result.set(mute.toggle(endpoint){true})}catch(e:Throwable){failure.set(e)}}
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));mute.cancel();assertTrue(client.closed)
        }finally{release.countDown();worker.join(3000)}
        assertFalse(worker.isAlive);assertNull(failure.get());assertEquals(false,result.get());assertEquals(0,client.toggles)
    }
}
