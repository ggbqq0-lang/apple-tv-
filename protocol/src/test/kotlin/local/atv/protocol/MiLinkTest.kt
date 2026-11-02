package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Independent loopback peer: never sends keys to a real television. */
class MiLinkTest {
    private data class Key(val id:Int,val fields:Map<Int,Long>) {
        val action get()=fields.getValue(2)
        val code get()=fields.getValue(3)
    }
    private fun key(socket:Socket):Key {
        val input=DataInputStream(socket.getInputStream())
        assertEquals(4,input.readUnsignedByte());val size=input.readUnsignedShort()
        val data=ByteArray(size);input.readFully(data);val body=DataInputStream(data.inputStream())
        assertEquals(1,body.readUnsignedByte());val id=body.readInt();assertEquals(size-7,body.readUnsignedShort())
        val fields=linkedMapOf<Int,Long>()
        while(body.available()>0){val tag=body.readUnsignedByte();fields[tag]=if(tag==7 || tag==8)body.readLong() else body.readInt().toLong()}
        return Key(id,fields)
    }
    private fun ack(socket:Socket,id:Int,fragmented:Boolean=false,code:Int=2){
        val data=java.io.ByteArrayOutputStream().also{DataOutputStream(it).use{out->
            out.writeByte(4);out.writeShort(7);out.writeByte(code);out.writeInt(id);out.writeShort(0)
        }}.toByteArray()
        val out=socket.getOutputStream()
        if(fragmented){for(byte in data){out.write(byte.toInt());out.flush();Thread.sleep(1)}}
        else {out.write(data);out.flush()}
    }
    private class Peer(private val block:(Socket)->Unit):AutoCloseable {
        private val server=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val endpoint=Endpoint("MiLink fixture","127.0.0.1",server.localPort)
        private val done=CompletableFuture<Unit>()
        private val worker=thread(isDaemon=true){
            try{server.accept().use{socket->
                socket.soTimeout=2000
                val heartbeat=ByteArray(3);DataInputStream(socket.getInputStream()).readFully(heartbeat)
                assertArrayEquals(byteArrayOf(2,0,0),heartbeat)
                socket.getOutputStream().run{write(heartbeat);flush()}
                block(socket)
            };done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}
        }
        fun finish(){done.get(5,TimeUnit.SECONDS)}
        override fun close(){server.close();worker.join(1000)}
    }
    @Test fun connectionAndUnsupportedFeaturesNeverSendKeys(){
        Peer{socket->assertEquals(-1,socket.getInputStream().read())}.use{peer->
            MiLink(peer.endpoint).use{client->
                client.connect();assertFalse(client.refreshText().focused)
                assertFalse(client.supportsText);assertFalse(client.supportsPower);assertFalse(client.supportsVolume);assertTrue(client.supportsVolumeSteps)
                assertTrue(client.supportsMuteToggle)
                assertEquals("TEXT_UNSUPPORTED",assertThrows(ProtocolFailure::class.java){client.sendText("public",false,true)}.code)
                assertEquals("POWER_UNSUPPORTED",assertThrows(ProtocolFailure::class.java){client.power(true)}.code)
                assertEquals("MUTE_UNAVAILABLE",assertThrows(ProtocolFailure::class.java){client.setVolumeVerified(0.0)}.code)
            };peer.finish()
        }
    }
    @Test fun mappingsHaveCorrectDownUpAndZeroTimestampsEvenWithDelayedFragmentedAcknowledgements(){
        val commands=listOf(1,2,3,4,5,6,7,8,9,14,5,7)
        val expected=listOf(19,20,21,22,4,23,3,24,25,85,3,187)
        Peer{socket->
            expected.forEachIndexed{index,code->
                val down=key(socket);val start=System.nanoTime();socket.soTimeout=200
                val up=key(socket);socket.soTimeout=2000
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<180)
                assertEquals(0L,down.action);assertEquals(1L,up.action);assertEquals(code.toLong(),down.code);assertEquals(down.code,up.code)
                assertNotEquals(down.id,up.id)
                for(k in listOf(down,up)){
                    assertEquals(0L,k.fields[7]);assertEquals(0L,k.fields[8]);assertEquals(-1L,k.fields[10]);assertEquals(0x101L,k.fields[11])
                }
                // The peer intentionally holds DOWN's ACK until UP is received.
                ack(socket,up.id,fragmented=index==0);ack(socket,down.id,fragmented=index==0)
            }
        }.use{peer->MiLink(peer.endpoint).use{client->client.connect();commands.forEachIndexed{i,c->client.press(c,i>=10)}};peer.finish()}
    }
    @Test fun heldSelectRepeatsThenReleasesWithoutWaitingForAcknowledgements(){
        val first=CountDownLatch(1);val released=CountDownLatch(1);val packets=mutableListOf<Key>()
        Peer{socket->
            while(true){val packet=key(socket);packets.add(packet);first.countDown();if(packet.action==1L){released.countDown();break}}
            assertTrue(packets.size>=3);assertTrue(packets.all{it.code==23L})
            assertEquals(0L,packets.first().fields[5]);assertTrue(packets.drop(1).dropLast(1).all{it.fields.getValue(5)>0})
            packets.reversed().forEach{ack(socket,it.id)}
        }.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();val held=RemoteHold();val done=CompletableFuture<Unit>()
            thread(isDaemon=true){try{client.holdSelect(held);done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}}
            assertTrue(first.await(2,TimeUnit.SECONDS));Thread.sleep(150);held.release()
            assertTrue(released.await(180,TimeUnit.MILLISECONDS));done.get(3,TimeUnit.SECONDS)
        };peer.finish()}
    }
    @Test fun closingDuringHoldSendsKeyUpAndEndsTheWorker(){
        val first=CountDownLatch(1)
        Peer{socket->
            val down=key(socket);assertEquals(0L,down.action);first.countDown()
            var up=key(socket);while(up.action==0L)up=key(socket)
            assertEquals(1L,up.action);assertEquals(23L,up.code);assertEquals(-1,socket.getInputStream().read())
        }.use{peer->
            val client=MiLink(peer.endpoint);client.connect();val held=RemoteHold();val done=CompletableFuture<Unit>()
            thread(isDaemon=true){try{client.holdSelect(held);done.complete(Unit)}catch(e:Throwable){done.completeExceptionally(e)}}
            assertTrue(first.await(2,TimeUnit.SECONDS));client.close();done.get(3,TimeUnit.SECONDS);assertFalse(held.active());peer.finish()
        }
    }
    @Test fun malformedAcknowledgementDoesNotReplayTheKey(){
        Peer{socket->
            val down=key(socket);key(socket);ack(socket,down.id,code=1)
            assertEquals(-1,socket.getInputStream().read())
        }.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();assertEquals("XIAOMI_FRAME_INVALID",assertThrows(ProtocolFailure::class.java){client.press(1)}.code)
        };peer.finish()}
    }
    @Test fun alreadyReleasedSelectIsNotSent(){
        Peer{socket->assertEquals(-1,socket.getInputStream().read())}.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();val held=RemoteHold();held.release();client.holdSelect(held)
        };peer.finish()}
    }
    @Test fun keepaliveDoesNotRequireAnImmediateReplyOrBlockTheNextKey(){
        Peer{socket->
            val bytes=ByteArray(3);DataInputStream(socket.getInputStream()).readFully(bytes)
            assertArrayEquals(byteArrayOf(2,0,0),bytes)
            // No heartbeat reply: key ACKs must remain independently usable.
            val down=key(socket);val up=key(socket)
            ack(socket,down.id);ack(socket,up.id)
        }.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();val start=System.nanoTime();client.heartbeat()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<180)
            client.press(6)
        };peer.finish()}
    }
    @Test fun connectionsAreRestrictedToLiteralLocalAddresses(){
        assertEquals("LOCAL_NETWORK_ONLY",assertThrows(ProtocolFailure::class.java){MiLink(Endpoint("fixture","8.8.8.8",6091)).connect()}.code)
        assertEquals("IP_ADDRESS_REQUIRED",assertThrows(ProtocolFailure::class.java){MiLink(Endpoint("fixture","example.com",6091)).connect()}.code)
    }
    @Test fun nativeMuteSendsOneSpeakerTogglePerTapWithoutVolumeOrMicrophoneKeys(){
        Peer{socket->
            val ids=mutableSetOf<Int>()
            repeat(2){
                val down=key(socket);val up=key(socket)
                assertEquals(164L,down.code);assertEquals(164L,up.code)
                assertEquals(0L,down.action);assertEquals(1L,up.action)
                assertEquals(0L,down.fields[5]);assertEquals(0L,up.fields[5])
                assertTrue(ids.add(down.id));assertTrue(ids.add(up.id))
                ack(socket,up.id);ack(socket,down.id)
            }
            assertEquals(-1,socket.getInputStream().read())
        }.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();client.toggleMute();client.toggleMute()
        };peer.finish()}
    }
    @Test fun unconfirmedMuteToggleIsNotRetried(){
        Peer{socket->
            val down=key(socket);val up=key(socket)
            assertEquals(164L,down.code);assertEquals(164L,up.code)
            ack(socket,down.id,code=1)
            assertEquals(-1,socket.getInputStream().read())
        }.use{peer->MiLink(peer.endpoint).use{client->
            client.connect();assertThrows(ProtocolFailure::class.java){client.toggleMute()}
        };peer.finish()}
    }
}
