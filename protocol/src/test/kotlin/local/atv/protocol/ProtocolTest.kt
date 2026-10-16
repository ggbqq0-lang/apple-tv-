package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Properties
import kotlin.concurrent.thread

class ProtocolTest {
    private val ref=Properties().apply {load(ProtocolTest::class.java.getResourceAsStream("/reference.properties"))}
    private fun bytes(key: String)=ref.getProperty(key).unhex()
    @Test fun srpMatchesIndependentPythonVectors(){repeat(2){i ->
        val srp=HapSrp("1111",bytes("srp$i.salt"),bytes("srp$i.B"),bytes("srp$i.private"))
        assertArrayEquals(bytes("srp$i.A"),srp.publicKey)
        assertArrayEquals(bytes("srp$i.K"),srp.sessionKey)
        assertArrayEquals(bytes("srp$i.M1"),srp.proof)
        assertArrayEquals(bytes("srp$i.M2"),srp.serverProof)
        srp.verifyServer(bytes("srp$i.M2"))
    }}
    @Test fun wrongServerProofRejected(){val s=HapSrp("1111",bytes("srp0.salt"),bytes("srp0.B"),bytes("srp0.private"));assertThrows(ProtocolFailure::class.java){s.verifyServer(ByteArray(64))}}
    @Test fun invalidSrpPublicRejected(){assertThrows(ProtocolFailure::class.java){HapSrp("1111",byteArrayOf(1),byteArrayOf(0))}}
    @Test fun cryptoMatchesIndependentPython(){
        val seed=ByteArray(32){it.toByte()}
        assertArrayEquals(bytes("ed.public"),Crypto.edPublic(seed));assertArrayEquals(bytes("ed.signature"),Crypto.sign(seed,utf8("fixture-message")))
        Crypto.verify(bytes("ed.public"),utf8("fixture-message"),bytes("ed.signature"))
        assertThrows(ProtocolFailure::class.java){Crypto.verify(bytes("ed.public"),utf8("changed"),bytes("ed.signature"))}
        assertArrayEquals(bytes("x.public"),Crypto.xPublic(seed));assertArrayEquals(bytes("hkdf"),Crypto.hkdf(seed,"","ClientEncrypt-main"))
    }
    @Test fun encryptionCountersAndAadMatchPython(){
        val key=ByteArray(32){it.toByte()};val reverse=key.reversedArray()
        val client=FrameCipher(key,reverse);val server=FrameCipher(reverse,key)
        repeat(2){i->val encoded=client.encode(8,bytes("frame.payload"));assertArrayEquals(bytes("frame$i"),encoded)
            assertArrayEquals(bytes("frame.payload"),server.decode(encoded.copyOfRange(0,4),encoded.copyOfRange(4,encoded.size)))}
        val corrupted=bytes("frame0");corrupted[3]=(corrupted[3]+1).toByte()
        assertThrows(ProtocolFailure::class.java){FrameCipher(reverse,key).decode(corrupted.copyOfRange(0,4),corrupted.copyOfRange(4,corrupted.size))}
    }
    @Test fun tlvSupportsLargeKeysAndTruncation(){val data=ByteArray(384){it.toByte()};assertArrayEquals(data,Tlv.decode(Tlv.encode(mapOf(3 to data)))[3]);assertThrows(ProtocolFailure::class.java){Tlv.decode(byteArrayOf(3,10,1))}}
    @Test fun credentialsRoundtripAndRedaction(){val c=Credentials(ByteArray(32),ByteArray(32){1},utf8("accessory"),utf8("client"));val d=Credentials.decode(c.encode());assertArrayEquals(c.privateKey,d.privateKey);assertFalse(c.toString().contains("accessory"))}
    @Test fun opackIndependentReferencePointers(){
        val decoded=Opack.unpack(bytes("opack.references")) as Map<*,*>
        assertEquals(List(40){"same-string"},decoded["repeated"]);assertEquals("Café😀",decoded["中文"])
        assertArrayEquals(ByteArray(16){it.toByte()},decoded["data"] as ByteArray)
        assertEquals("same-string",decoded["repeat2"])
        val long=(0..40).associate{"key$it" to List(20){"duplicate"}}
        assertEquals(long,Opack.unpack(Opack.pack(long)))
    }
    @Test fun opackGolden(){assertEquals("e3425f69475f746953746f70425f740a425f63e0",Opack.pack(linkedMapOf("_i" to "_tiStop","_t" to 2,"_c" to emptyMap<String,Any?>())).hex())}
    @Test fun malformedOpackRejected(){listOf(byteArrayOf(),byteArrayOf(0xc0.toByte()),byteArrayOf(0x62,0xff.toByte()),byteArrayOf(0xe1.toByte(),0x41,0x61)).forEach{v->assertThrows(ProtocolFailure::class.java){Opack.unpack(v)}}}
    @Test fun rtiAndUnicodeRoundtripAcrossAllLengths(){
        val uuid=ByteArray(16){it.toByte()};val dir=File("build/rti-cross-check").apply{mkdirs()}
        repeat(ref.getProperty("case.count").toInt()){i->
            val text=bytes("case$i.text").toString(Charsets.UTF_8)
            val path=arrayOf("textOperations","keyboardOutput","insertionText")
            assertEquals(text,Rti.property(bytes("case$i.rti"),*path))
            val own=Rti.operation(uuid,text,false);assertEquals(text,Rti.property(own,*path))
            assertArrayEquals(uuid,Rti.property(own,"textOperations","targetSessionUUID","NS.uuidbytes") as ByteArray)
            File(dir,"case$i.bplist").writeBytes(own)
        }
        assertEquals("",Rti.property(Rti.operation(uuid,"",true),"textOperations","textToAssert"))
    }
    @Test fun readSessionArchiveFromPython(){assertArrayEquals(ByteArray(16){it.toByte()},Rti.session(bytes("session.archive")));assertEquals("中文&%2F",Rti.context(bytes("session.archive")))}
    @Test fun plistRejectsInvalidInput(){assertThrows(ProtocolFailure::class.java){Plist.decode(byteArrayOf(1,2))};assertThrows(ProtocolFailure::class.java){Rti.operation(ByteArray(2),"hello",false)}}
    @Test fun networkTimeoutAndDisconnect(){
        ServerSocket(0).use {server->
            val worker=thread {server.accept().use {Thread.sleep(350)}}
            Channel(Endpoint("test","127.0.0.1",server.localPort),100).use {ch->assertThrows(Exception::class.java){ch.request("timeout")}}
            worker.join()
        }
        ServerSocket(0).use {server->val worker=thread {server.accept().close()}
            Channel(Endpoint("test","127.0.0.1",server.localPort),200).use{ch->assertThrows(Exception::class.java){ch.request("closed")}};worker.join()}
    }
    @Test fun fragmentedFramesAndAsyncEvents(){
        ServerSocket(0).use {server->
            val worker=thread {server.accept().use {socket->
                val input=java.io.DataInputStream(socket.getInputStream());val header=ByteArray(4);input.readFully(header)
                val n=((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                val payload=ByteArray(n);input.readFully(payload);val request=Opack.unpack(payload) as Map<*,*>
                val event=Opack.pack(mapOf("_i" to "_tiStopped","_t" to 1,"_c" to emptyMap<String,Any?>()))
                val response=Opack.pack(mapOf("_t" to 3,"_x" to request["_x"],"_c" to mapOf("ok" to true)))
                val data=FrameCipher.header(8,event.size)+event+FrameCipher.header(8,response.size)+response
                for(chunk in data.toList().chunked(3)){socket.getOutputStream().write(chunk.toByteArray());socket.getOutputStream().flush()}
            }}
            Channel(Endpoint("test","127.0.0.1",server.localPort)).use{ch->var event=false;ch.onEvent={name,_->event=name=="_tiStopped"}
                val response=ch.request("hello");assertTrue(event);assertEquals(true,(response["_c"] as Map<*,*>)["ok"])};worker.join()
        }
    }
    @Test fun noUnauthenticatedTextOrRemoteControl(){
        ServerSocket(0).use{server->val worker=thread{server.accept().use{Thread.sleep(200)}}
            Companion(Endpoint("test","127.0.0.1",server.localPort),Credentials(ByteArray(32),ByteArray(32),utf8("a"),utf8("c"))).use{client->
                assertThrows(ProtocolFailure::class.java){client.sendText("hello",false,true)}
                assertThrows(ProtocolFailure::class.java){client.press(1)}
            };worker.join()}
    }
}
