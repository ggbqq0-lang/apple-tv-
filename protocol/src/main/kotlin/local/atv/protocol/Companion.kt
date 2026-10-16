package local.atv.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID

data class Endpoint(val name: String, val host: String, val port: Int) {
    override fun toString() = "$name ($host:$port)"
}
data class Frame(val type: Int, val payload: ByteArray)

class FrameCipher(private val outputKey: ByteArray, private val inputKey: ByteArray) {
    private var outputCounter=0L;private var inputCounter=0L
    private fun nonce(counter: Long) = ByteArray(12){if(it<8)(counter ushr (8*it)).toByte() else 0}
    fun encode(type: Int, payload: ByteArray): ByteArray {
        val header=header(type,if(payload.isEmpty())0 else payload.size+16)
        return header + if(payload.isEmpty())payload else Crypto.chacha(outputKey,nonce(outputCounter++),payload,header,true)
    }
    fun decode(header: ByteArray, payload: ByteArray): ByteArray = if(payload.isEmpty())payload else Crypto.chacha(inputKey,nonce(inputCounter++),payload,header,false)
    companion object {
        fun header(type: Int,length: Int): ByteArray {ensure(length in 0..1024*1024,"FRAME_TOO_LARGE");return byteArrayOf(type.toByte(),(length ushr 16).toByte(),(length ushr 8).toByte(),length.toByte())}
    }
}

/** Single owner, serialized blocking I/O. Each request has a wall-clock deadline. */
class Channel(endpoint: Endpoint, private val timeout: Int=6000) : Closeable {
    private val socket=Socket()
    private val input: DataInputStream
    private var cipher: FrameCipher?=null
    private var xid=(System.nanoTime() and 65535).toLong()
    private var activeKey:Int?=null
    var onEvent: (String,Map<String,Any?>)->Unit = {_,_->}
    init {
        ensure(endpoint.port in 1..65535,"INVALID_PORT")
        ensure(endpoint.host.matches(Regex("[0-9a-fA-F:.%a-zA-Z]+")) && (endpoint.host.contains(':') || endpoint.host.matches(Regex("[0-9.]+"))),"IP_ADDRESS_REQUIRED")
        val address=InetAddress.getByName(endpoint.host)
        ensure(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            (address.address.size==16 && address.address[0].toInt() and 0xfe==0xfc),"LOCAL_NETWORK_ONLY")
        try {socket.connect(InetSocketAddress(address,endpoint.port),timeout);socket.tcpNoDelay=true;socket.soTimeout=timeout;input=DataInputStream(socket.getInputStream())}
        catch(error: Exception){socket.close();throw error}
    }
    fun encrypted(output: ByteArray,input: ByteArray){cipher=FrameCipher(output,input)}
    @Synchronized private fun send(type: Int,payload: ByteArray){
        val frame=cipher?.encode(type,payload) ?: (FrameCipher.header(type,payload.size)+payload)
        socket.getOutputStream().run{write(frame);flush()}
    }
    private fun read(deadline: Long): Frame {
        fun exact(size: Int): ByteArray = ByteArray(size).also {buffer ->
            var done=0
            while(done<size){val remaining=(deadline-System.nanoTime())/1000000;ensure(remaining>0,"REQUEST_TIMEOUT")
                socket.soTimeout=minOf(timeout.toLong(),remaining).coerceAtLeast(1).toInt()
                val n=input.read(buffer,done,size-done);ensure(n>0,"CONNECTION_CLOSED");done+=n}
        }
        val header=exact(4);val length=((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        ensure(length<=1024*1024,"FRAME_TOO_LARGE");val payload=exact(length)
        return Frame(header[0].toInt() and 255,cipher?.decode(header,payload) ?: payload)
    }
    @Suppress("UNCHECKED_CAST")
    fun exchange(type: Int, message: Map<String,Any?>, auth: Boolean=false): Map<String,Any?> {
        val id=xid++;send(type,Opack.pack(message+mapOf("_x" to id)))
        val deadline=System.nanoTime()+timeout*1000000L
        while(true){val frame=read(deadline);if(frame.payload.isEmpty())continue
            val decoded=Opack.unpack(frame.payload) as? Map<String,Any?> ?: throw ProtocolFailure("MESSAGE_TYPE")
            if(auth && frame.type==(if(type==3)4 else if(type==5)6 else type))return decoded
            if(!auth && frame.type==8){
                val mt=(decoded["_t"] as? Number)?.toInt()
                if(mt==1)onEvent(decoded["_i"] as? String ?: "",decoded["_c"] as? Map<String,Any?> ?: emptyMap())
                if(mt==3 && (decoded["_x"] as? Number)?.toLong()==id){ensure(!decoded.containsKey("_em") && ((decoded["_ec"] as? Number)?.toInt() ?: 0)==0,"COMMAND_REJECTED");return decoded}
            }
        }
    }
    fun event(identifier: String,content: Map<String,Any?>){send(8,Opack.pack(linkedMapOf("_i" to identifier,"_t" to 1,"_c" to content,"_x" to xid++)))}
    fun request(identifier: String,content: Map<String,Any?> = emptyMap()): Map<String,Any?> = exchange(8,linkedMapOf("_i" to identifier,"_t" to 2,"_c" to content))
    private fun keyPacket(id:Long,command:Int,state:Int)=Opack.pack(mapOf("_i" to "_hidC","_t" to 2,"_x" to id,"_c" to mapOf("_hBtS" to state,"_hidC" to command)))
    fun pressAndRelease(command:Int,hold:Boolean=false,held:RemoteHold?=null){
        if(held!=null && !held.active())return
        val down=xid++;var up=0L
        // Deliver key-up without waiting for key-down's acknowledgement. A slow
        // reply must not turn a tap into a held, auto-repeating direction.
        try{
            synchronized(this){activeKey=command;send(8,keyPacket(down,command,1))}
            if(held!=null)held.awaitRelease() else Thread.sleep(if(hold)1000 else 25)
        }finally{synchronized(this){up=xid++;send(8,keyPacket(up,command,2));activeKey=null}}
        val pending=mutableSetOf(down,up);var rejected=false
        val deadline=System.nanoTime()+timeout*1000000L
        while(pending.isNotEmpty()){
            val frame=read(deadline);if(frame.type!=8 || frame.payload.isEmpty())continue
            @Suppress("UNCHECKED_CAST")
            val message=Opack.unpack(frame.payload) as? Map<String,Any?> ?: throw ProtocolFailure("MESSAGE_TYPE")
            if((message["_t"] as? Number)?.toInt()==1){
                @Suppress("UNCHECKED_CAST")
                onEvent(message["_i"] as? String ?: "",message["_c"] as? Map<String,Any?> ?: emptyMap())
            }else if((message["_t"] as? Number)?.toInt()==3 && pending.remove((message["_x"] as? Number)?.toLong())){
                rejected=rejected || message.containsKey("_em") || ((message["_ec"] as? Number)?.toInt() ?: 0)!=0
            }
        }
        ensure(!rejected,"COMMAND_REJECTED")
    }
    fun auth(type: Int,values: Map<Int,ByteArray>,extra: Map<String,Any?> = emptyMap()): Map<Int,ByteArray> {
        val response=exchange(type,linkedMapOf("_pd" to Tlv.encode(values))+extra,true)
        val tlv=Tlv.decode(response["_pd"] as? ByteArray ?: throw ProtocolFailure("AUTH_FIELD_MISSING"))
        tlv[7]?.let {val n=it.firstOrNull()?.toInt() ?: 0;throw ProtocolFailure(when(n){2->"PIN_OR_CREDENTIALS_REJECTED";3->"PAIRING_BACKOFF";4->"PAIRING_MAX_PEERS";5->"PAIRING_MAX_TRIES";7->"PAIRING_BUSY";else->"PAIRING_REJECTED"})}
        return tlv
    }
    @Synchronized override fun close(){
        // Lifecycle cancellation may race the IO hold worker. Release on the
        // same write lock before closing, even if the worker has not resumed.
        activeKey?.let{command->try{send(8,keyPacket(xid++,command,2))}catch(_:Exception){}}
        activeKey=null;try{socket.close()}catch(_:Exception){}
    }
}

class Pairing(endpoint: Endpoint) : Closeable {
    private val channel=Channel(endpoint)
    private val seed=Crypto.random(32)
    private val clientId=utf8(UUID.randomUUID().toString())
    private var salt: ByteArray?=null;private var publicKey: ByteArray?=null
    private var started=0L
    fun begin(){
        val response=channel.auth(3,linkedMapOf(0 to byteArrayOf(0),6 to byteArrayOf(1)),mapOf("_pwTy" to 1))
        ensure(response.required(6).contentEquals(byteArrayOf(2)),"PAIRING_SEQUENCE")
        salt=response.required(2);publicKey=response.required(3);started=System.nanoTime()
    }
    fun finish(pin: String): Credentials {
        ensure(pin.matches(Regex("[0-9]{4}")),"FOUR_DIGIT_PIN_REQUIRED")
        ensure(started>0 && System.nanoTime()-started<120000000000L,"PIN_EXPIRED")
        val srp=HapSrp(pin,salt ?: throw ProtocolFailure("PAIRING_NOT_STARTED"),publicKey ?: throw ProtocolFailure("PAIRING_NOT_STARTED"),seed)
        val m4=channel.auth(4,linkedMapOf(6 to byteArrayOf(3),3 to srp.publicKey,4 to srp.proof),mapOf("_pwTy" to 1))
        ensure(m4.required(6).contentEquals(byteArrayOf(4)),"PAIRING_SEQUENCE");srp.verifyServer(m4.required(4))
        val controller=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info")
        val key=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info")
        val edPublic=Crypto.edPublic(seed)
        val inner=Tlv.encode(linkedMapOf(1 to clientId,3 to edPublic,10 to Crypto.sign(seed,controller+clientId+edPublic),17 to Opack.pack(mapOf("name" to "Apple TV 快捷输入助手"))))
        val m6=channel.auth(4,linkedMapOf(6 to byteArrayOf(5),5 to Crypto.chacha(key,utf8("PS-Msg05"),inner,encrypt=true)),mapOf("_pwTy" to 1))
        ensure(m6.required(6).contentEquals(byteArrayOf(6)),"PAIRING_SEQUENCE")
        val device=Tlv.decode(Crypto.chacha(key,utf8("PS-Msg06"),m6.required(5),encrypt=false))
        val accessory=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Accessory-Sign-Salt","Pair-Setup-Accessory-Sign-Info")
        Crypto.verify(device.required(3),accessory+device.required(1)+device.required(3),device.required(10))
        return Credentials(device.required(3),seed.copyOf(),device.required(1),clientId)
    }
    override fun close(){channel.close();seed.fill(0)}
}

data class TextState(val focused: Boolean,val sessionId: ByteArray?=null,val context: String?=null) {
    override fun toString() = "TextState(focused=$focused, [redacted])"
}
data class SendResult(val contextMatches: Boolean?,val characters: Int,val utf8Bytes: Int)

class Companion(endpoint: Endpoint, private val credentials: Credentials) : Closeable {
    private val channel=Channel(endpoint)
    private var authenticated=false
    @Volatile private var selectHold:RemoteHold?=null
    var focused=false;private set
    var supportsVolume=false;private set
    var supportsVolumeSteps=false;private set
    private var sid=0L
    private var textStarted=false
    private var touchTimestamp=System.nanoTime()
    fun connect(){
        val ephemeral=Crypto.random(32);val clientPublic=Crypto.xPublic(ephemeral)
        val m2=channel.auth(5,linkedMapOf(6 to byteArrayOf(1),3 to clientPublic),mapOf("_auTy" to 4))
        ensure(m2.required(6).contentEquals(byteArrayOf(2)),"VERIFY_SEQUENCE")
        val devicePublic=m2.required(3);val shared=Crypto.shared(ephemeral,devicePublic);ephemeral.fill(0)
        val verifyKey=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
        val device=Tlv.decode(Crypto.chacha(verifyKey,utf8("PV-Msg02"),m2.required(5),encrypt=false))
        ensure(MessageDigest.isEqual(device.required(1),credentials.accessoryId),"DEVICE_IDENTITY_CHANGED")
        Crypto.verify(credentials.publicKey,devicePublic+device.required(1)+clientPublic,device.required(10))
        val response=Tlv.encode(linkedMapOf(1 to credentials.clientId,10 to Crypto.sign(credentials.privateKey,clientPublic+credentials.clientId+devicePublic)))
        val m4=channel.auth(6,linkedMapOf(6 to byteArrayOf(3),5 to Crypto.chacha(verifyKey,utf8("PV-Msg03"),response,encrypt=true)))
        ensure(m4.required(6).contentEquals(byteArrayOf(4)),"VERIFY_SEQUENCE")
        channel.encrypted(Crypto.hkdf(shared,"","ClientEncrypt-main"),Crypto.hkdf(shared,"","ServerEncrypt-main"));shared.fill(0);authenticated=true;supportsVolumeSteps=true
        channel.onEvent={name,content ->
            if(name in listOf("_tiStarted","_tiStopped"))focused=content["_tiD"] is ByteArray
            if(name=="_iMC")supportsVolume=((content["_mcF"] as? Number)?.toInt() ?: 0) and 0x0100 != 0
        }
        val client=credentials.clientId.toString(Charsets.UTF_8)
        request("_systemInfo",linkedMapOf("_bf" to 0,"_cf" to 512,"_clFl" to 128,"_i" to client,"_idsID" to credentials.clientId,"_pubID" to "02:00:00:00:00:01","_sf" to 256,"_sv" to "170.18","model" to "iPhone14,3","name" to "Apple TV 快捷输入助手"))
        touchTimestamp=System.nanoTime()
        request("_touchStart",mapOf("_height" to 1000.0,"_width" to 1000.0,"_tFl" to 0))
        touch(500,500,4) // Clear any touch left by an interrupted earlier session.
        val local=(System.nanoTime() and 0xffffffffL)
        val remote=((request("_sessionStart",mapOf("_srvT" to "com.apple.tvremoteservices","_sid" to local))["_c"] as? Map<*,*>)?.get("_sid") as? Number)?.toLong() ?: throw ProtocolFailure("SESSION_ID_MISSING")
        sid=(remote shl 32) or local
        try{request("TVRCSessionStart",mapOf("ProtocolVersionKey" to "1.2"))}catch(e:ProtocolFailure){if(e.code!="COMMAND_REJECTED")throw e}
        channel.event("_interest",mapOf("_regEvents" to listOf("_iMC")))
        refreshText()
    }
    private fun request(id: String,content: Map<String,Any?> = emptyMap()): Map<String,Any?> {ensure(authenticated,"PAIRING_REQUIRED");return channel.request(id,content)}
    fun refreshText(): TextState {
        if(textStarted)request("_tiStop")
        val content=request("_tiStart")["_c"] as? Map<*,*>
        textStarted=true
        val archive=content?.get("_tiD") as? ByteArray
        focused=archive!=null
        if(archive==null)return TextState(false)
        val uuid=Rti.session(archive);ensure(uuid?.size==16,"RTI_SESSION_UUID")
        return TextState(true,uuid,Rti.context(archive))
    }
    fun sendText(text: String,append: Boolean,confirmReplace: Boolean,livePolicy: LiveTextPolicy?=null): SendResult {
        ensure(append || confirmReplace,"REPLACE_CONFIRMATION_REQUIRED")
        ensure(!text.any {it.isSurrogate()} || validSurrogates(text),"INVALID_UNICODE")
        val encoded=utf8(text);ensure(encoded.size<=65536,"LOCAL_TEXT_LIMIT_64K")
        val before=refreshText();ensure(before.focused && before.sessionId!=null,"NO_FOCUSED_TEXT_FIELD")
        livePolicy?.check(before.sessionId)
        val expected=if(append)before.context?.plus(text) else text
        try{
            if(!append)channel.event("_tiC",mapOf("_tiV" to 1,"_tiD" to Rti.operation(before.sessionId!!,"",true)))
            if(text.isNotEmpty())channel.event("_tiC",mapOf("_tiV" to 1,"_tiD" to Rti.operation(before.sessionId!!,text,false)))
            Thread.sleep(300)
            val after=try{refreshText()}catch(_:Exception){null}
            val matches=if(after?.context!=null && expected!=null && after.sessionId?.contentEquals(before.sessionId)==true)after.context==expected else null
            return SendResult(matches,text.codePointCount(0,text.length),encoded.size)
        }catch(_:Exception){throw ProtocolFailure("WRITE_OUTCOME_UNKNOWN")}
    }
    fun press(command: Int,hold:Boolean=false){
        ensure(command in listOf(1,2,3,4,5,6,7,8,9,14),"REMOTE_UNSUPPORTED")
        val action:()->Unit={
            ensure(authenticated,"PAIRING_REQUIRED");channel.pressAndRelease(command,hold)
        }
        if(command==8 || command==9){
            ensure(supportsVolumeSteps,"VOLUME_KEYS_UNAVAILABLE")
            try{action()}catch(e:ProtocolFailure){if(e.code!="COMMAND_REJECTED")throw e;supportsVolumeSteps=false;throw ProtocolFailure("VOLUME_KEYS_UNAVAILABLE")}
        }else action()
    }
    fun power(wake:Boolean){request("_hidC",mapOf("_hBtS" to 2,"_hidC" to if(wake)13 else 12))}
    fun holdSelect(held:RemoteHold){
        ensure(authenticated,"PAIRING_REQUIRED")
        selectHold=held
        try{channel.pressAndRelease(6,held=held)}finally{if(selectHold===held)selectHold=null}
    }
    private fun <T> withAudioSupport(action:()->T):T {
        ensure(supportsVolume,"AUDIO_UNSUPPORTED")
        return try{action()}catch(e:ProtocolFailure){
            if(e.code!="COMMAND_REJECTED")throw e
            supportsVolume=false;throw ProtocolFailure("AUDIO_UNSUPPORTED")
        }
    }
    private fun audioRequest(content:Map<String,Any?>)=withAudioSupport{request("_mcc",content)}
    fun volume():Double {
        val level=(audioRequest(mapOf("_mcc" to 5))["_c"] as? Map<*,*>)?.get("_vol") as? Number
        if(level==null || !level.toDouble().isFinite() || level.toDouble() !in 0.0..1.0){supportsVolume=false;throw ProtocolFailure("AUDIO_UNSUPPORTED")}
        return level.toDouble()
    }
    fun setVolume(level:Double){ensure(level.isFinite() && level in 0.0..1.0,"VOLUME_RANGE");audioRequest(mapOf("_mcc" to 6,"_vol" to level))}
    fun setVolumeVerified(level:Double){
        setVolume(level)
        repeat(10){Thread.sleep(100);if(kotlin.math.abs(volume()-level)<.005)return}
        throw ProtocolFailure("MUTE_UNAVAILABLE")
    }
    fun touch(x: Int,y: Int,phase: Int){
        ensure(authenticated,"PAIRING_REQUIRED");ensure(phase in listOf(1,3,4,5),"TOUCH_PHASE_INVALID")
        channel.event("_hidT",linkedMapOf("_ns" to (System.nanoTime()-touchTimestamp),"_tFg" to 1,
            "_cx" to x.coerceIn(0,1000),"_tPh" to phase,"_cy" to y.coerceIn(0,1000)))
    }
    fun tap(x: Int,y: Int){press(6);touch(x,y,5)}
    override fun close(){selectHold?.release();authenticated=false;focused=false;supportsVolume=false;supportsVolumeSteps=false;channel.close()}
    private fun validSurrogates(s: String): Boolean {var i=0;while(i<s.length){val c=s[i++];if(c.isHighSurrogate()){if(i>=s.length || !s[i++].isLowSurrogate())return false}else if(c.isLowSurrogate())return false};return true}
}
