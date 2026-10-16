package local.atv.protocol

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Properties
import java.io.File
import kotlin.concurrent.thread

/** The peer here is pyatv's independent fake, NOT an actual Apple TV. */
class CompanionIntegrationTest {
    @Test fun pairAuthenticateEncryptAndSendWithUpstreamFake(){
        val port=System.getenv("ATV_FAKE_PORT")?.toIntOrNull()
        assumeTrue("Set ATV_FAKE_PORT to the loopback pyatv fake",port!=null)
        val endpoint=Endpoint("pyatv independent fake","127.0.0.1",port!!)
        val credentials=Pairing(endpoint).use{p->p.begin();p.finish("1111")}
        Companion(endpoint,credentials).use{client->
            client.connect();assertTrue(client.focused)
            val reference=Properties().apply{load(CompanionIntegrationTest::class.java.getResourceAsStream("/reference.properties"))}
            repeat(reference.getProperty("case.count").toInt()){i->
                val text=reference.getProperty("case$i.text").unhex().toString(Charsets.UTF_8)
                val result=client.sendText(text,false,true)
                assertEquals("case$i",true,result.contextMatches)
                assertEquals(text,client.refreshText().context)
            }
            val before=client.refreshText().context
            assertEquals(true,client.sendText("中文&%2F",true,false).contextMatches)
            assertEquals(before+"中文&%2F",client.refreshText().context)
            assertEquals(true,client.sendText("",false,true).contextMatches)
            assertEquals("",client.refreshText().context)
            listOf(1,2,3,4,5,6,7,14).forEach{client.press(it)}
            val pressStarted=System.nanoTime();client.press(1)
            if(System.getenv("ATV_FAKE_DELAY_HID_ACK")=="1")assertTrue("Delayed-ACK fixture did not run",(System.nanoTime()-pressStarted)/1000000>=250)
            System.getenv("ATV_FAKE_METRICS")?.let{path->
                val timing=Properties().apply{File(path+".keys").inputStream().use{load(it)}}
                assertEquals("1",timing.getProperty("command"))
                assertTrue("Key-up waited for delayed key-down ACK",timing.getProperty("held_ms").toDouble()<200)
            }
            // These power/audio operations target the loopback fake only.
            client.press(7,hold=true)
            val held=RemoteHold();val release=thread{Thread.sleep(650);held.release()}
            client.holdSelect(held);release.join();client.refreshText() // metrics file is written after the up ACK
            System.getenv("ATV_FAKE_METRICS")?.let{path->
                val timing=Properties().apply{File(path+".keys").inputStream().use{load(it)}}
                assertEquals("6",timing.getProperty("command"));assertTrue(timing.getProperty("held_ms").toDouble() in 450.0..950.0)
                val before=File(path+".keys").readText()
                client.holdSelect(RemoteHold().apply{release()});client.refreshText()
                assertEquals("Released queued hold must not produce a key press",before,File(path+".keys").readText())
            }
            client.power(false);client.power(true)
            assertTrue(client.supportsVolume)
            client.setVolume(.42);assertEquals(.42,client.volume(),.001)
            val mute=NetworkMute()
            assertTrue(mute.toggle(client::volume,client::setVolumeVerified));assertEquals(0.0,client.volume(),.001)
            assertFalse(mute.toggle(client::volume,client::setVolumeVerified));assertEquals(.42,client.volume(),.001)
            client.press(8);assertTrue(client.volume()>.42)
            client.press(9)
            client.setVolume(0.0);assertEquals(0.0,client.volume(),.001)
            client.setVolume(.42);assertEquals(.42,client.volume(),.001)
            assertThrows(ProtocolFailure::class.java){client.setVolume(Double.NaN)}
            client.touch(100,500,1)
            repeat(10){client.touch(180+it*80,500,3)}
            client.touch(900,500,4)
            client.refreshText() // Barrier: independent server processed preceding events.
            System.getenv("ATV_FAKE_METRICS")?.let{path->
                val metrics=Properties().apply{File(path).inputStream().use{load(it)}}
                assertEquals("4",metrics.getProperty("phase"));assertEquals("900",metrics.getProperty("x"))
                assertEquals("500",metrics.getProperty("y"));assertEquals("13",metrics.getProperty("count"))
                assertEquals("true",metrics.getProperty("ordered"))
            }
            client.tap(900,500);client.refreshText()
            System.getenv("ATV_FAKE_METRICS")?.let{path->
                val metrics=Properties().apply{File(path).inputStream().use{load(it)}}
                assertEquals("5",metrics.getProperty("phase"));assertEquals("14",metrics.getProperty("count"))
            }
            val live=LiveTextPolicy();live.arm(client.refreshText().sessionId)
            listOf("A","Ab","Ab中","Ab中文","Ab中","").forEach{assertEquals(true,client.sendText(it,false,true,live).contextMatches)}
            live.stop()
            assertThrows(ProtocolFailure::class.java){client.sendText("must-not-write",false,true,live)}
            assertEquals("",client.refreshText().context)
        }
        // Reuse saved credentials for a wholly new connection and fresh counters.
        Companion(endpoint,Credentials.decode(credentials.encode())).use{it.connect();assertTrue(it.refreshText().focused)}
    }
    @Test fun wrongPinMustFailWithUpstreamFake(){
        val port=System.getenv("ATV_FAKE_PORT")?.toIntOrNull();assumeTrue(port!=null)
        Pairing(Endpoint("fake","127.0.0.1",port!!)).use{p->p.begin();assertThrows(ProtocolFailure::class.java){p.finish("9999")}}
    }
    @Test fun closingClientReleasesHeldSelect(){
        val port=System.getenv("ATV_FAKE_PORT")?.toIntOrNull();val metrics=System.getenv("ATV_FAKE_METRICS")
        assumeTrue(port!=null && metrics!=null)
        val endpoint=Endpoint("fake","127.0.0.1",port!!)
        val credentials=Pairing(endpoint).use{it.begin();it.finish("1111")}
        val client=Companion(endpoint,credentials);client.connect()
        val stateFile=File(metrics+".key-state");stateFile.delete()
        val worker=thread{try{client.holdSelect(RemoteHold())}catch(_:Exception){}}
        try{
            val deadline=System.nanoTime()+2000000000
            while(System.nanoTime()<deadline && (!stateFile.exists() || !stateFile.readText().contains("pressed=true")))Thread.sleep(10)
            assertTrue(stateFile.exists() && stateFile.readText().contains("pressed=true"))
            client.close();worker.join(1000);assertFalse(worker.isAlive)
            val end=System.nanoTime()+1000000000
            while(System.nanoTime()<end && stateFile.readText().contains("pressed=true"))Thread.sleep(10)
            assertTrue(stateFile.readText().contains("pressed=false"))
        }finally{client.close();worker.join(2000)}
    }
}
