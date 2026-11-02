package local.atv.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Independent implementation of MiLink's published key-event wire format. */
object MiLinkWire {
    fun key(id:Long,keyCode:Int,action:Int,repeat:Int=0):ByteArray {
        ensure(action in 0..1 && repeat>=0,"XIAOMI_KEY_INVALID")
        val data=ByteArrayOutputStream().also{buffer->DataOutputStream(buffer).use{out->
            fun int(tag:Int,value:Int){out.writeByte(tag);out.writeInt(value)}
            int(1,0);int(2,action);int(3,keyCode);int(4,0);int(5,repeat);int(6,0)
            out.writeByte(7);out.writeLong(0);out.writeByte(8);out.writeLong(0)
            int(10,-1);int(11,0x101)
        }}.toByteArray()
        return ByteArrayOutputStream().also{buffer->DataOutputStream(buffer).use{out->
            out.writeByte(4);out.writeShort(data.size+7);out.writeByte(1);out.writeInt(id.toInt());out.writeShort(data.size);out.write(data)
        }}.toByteArray()
    }
    fun command(command:Int,hold:Boolean=false):Pair<Int,Boolean> = when {
        hold && command==5 -> 3 to false // long Back: home
        hold && command==7 -> 187 to false // long TV/Home: recents
        else -> (when(command){1->19;2->20;3->21;4->22;5->4;6->23;7->3;8->24;9->25;14->85;else->throw ProtocolFailure("REMOTE_UNSUPPORTED")}) to hold
    }
}

/** Serialized blocking I/O. Connecting and polling send only heartbeat frames. */
class MiLink(private val endpoint:Endpoint,private val timeout:Int=3000):RemoteSession {
    private val socket=Socket()
    private var nextId=0L
    private var activeKey:Int?=null
    @Volatile private var closed=false
    @Volatile private var heldSelect:RemoteHold?=null
    private var lastHeartbeat=0L
    override val focused=false
    override val supportsText=false
    override val supportsPower=false
    override val supportsMuteToggle=true
    override val supportsVolume=false
    override val supportsVolumeSteps=true
    override fun connect(){
        ensure(endpoint.port in 1..65535,"INVALID_PORT")
        ensure(endpoint.host.matches(Regex("[0-9a-fA-F:.%a-zA-Z]+")) &&
            (endpoint.host.contains(':') || endpoint.host.matches(Regex("[0-9.]+"))),"IP_ADDRESS_REQUIRED")
        val address=InetAddress.getByName(endpoint.host)
        ensure(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress ||
            (address.address.size==16 && address.address[0].toInt() and 0xfe==0xfc),"LOCAL_NETWORK_ONLY")
        try{socket.connect(InetSocketAddress(address,endpoint.port),timeout);socket.tcpNoDelay=true;socket.soTimeout=timeout;heartbeat(expectReply=true)}
        catch(e:Exception){close();throw e}
    }
    private fun deadline()=System.nanoTime()+timeout*1000000L
    private fun readFrame(until:Long):Pair<Int,ByteArray>{
        fun exact(size:Int):ByteArray=ByteArray(size).also{data->
            var offset=0
            while(offset<size){
                val remaining=(until-System.nanoTime())/1000000
                ensure(remaining>0,"REQUEST_TIMEOUT");socket.soTimeout=minOf(timeout.toLong(),remaining).coerceAtLeast(1).toInt()
                val count=socket.getInputStream().read(data,offset,size-offset);ensure(count>0,"CONNECTION_CLOSED");offset+=count
            }
        }
        val header=exact(3);val size=((header[1].toInt() and 255) shl 8) or (header[2].toInt() and 255)
        ensure(size<=4096,"XIAOMI_FRAME_INVALID")
        return (header[0].toInt() and 255) to exact(size)
    }
    @Synchronized private fun write(data:ByteArray){
        ensure(!closed && socket.isConnected,"CONNECTION_CLOSED")
        socket.getOutputStream().run{write(data);flush()}
    }
    fun heartbeat(expectReply:Boolean=false){
        write(byteArrayOf(2,0,0));lastHeartbeat=System.nanoTime()
        // Some TVs throttle consecutive heartbeat replies. Only the initial
        // service check waits for a response; key events have their own ACKs.
        if(!expectReply)return
        val until=deadline()
        repeat(192){val (type,data)=readFrame(until)
            if(type==2 && data.isEmpty()){lastHeartbeat=System.nanoTime();return}
            ensure(type==4,"XIAOMI_FRAME_INVALID")
        };throw ProtocolFailure("XIAOMI_FRAME_INVALID")
    }
    override fun refreshText():TextState {
        if(System.nanoTime()-lastHeartbeat>=5000000000L)heartbeat()
        repeat(192){
            if(socket.getInputStream().available()==0)return TextState(false)
            val (type,data)=readFrame(deadline())
            ensure((type==2 && data.isEmpty()) || (type==4 && data.size>=7),"XIAOMI_FRAME_INVALID")
        }
        return TextState(false)
    }
    @Synchronized private fun key(code:Int,action:Int,repeat:Int=0):Long {
        val id=(++nextId) and 0xffffffffL;write(MiLinkWire.key(id,code,action,repeat));return id
    }
    private fun acknowledged(ids:MutableSet<Long>){
        val until=deadline()
        repeat(192){
            if(ids.isEmpty())return
            val (type,data)=readFrame(until)
            if(type==2 && data.isEmpty())return@repeat
            ensure(type==4 && data.size>=7,"XIAOMI_FRAME_INVALID")
            val input=DataInputStream(data.inputStream());val code=input.readUnsignedByte();val id=input.readInt().toLong() and 0xffffffffL
            ensure(code==2 && input.readUnsignedShort()==data.size-7,"XIAOMI_FRAME_INVALID")
            ids.remove(id)
        };ensure(ids.isEmpty(),"XIAOMI_COMMAND_UNCONFIRMED")
    }
    private fun holdKey(code:Int,held:RemoteHold){
        if(!held.active())return
        heldSelect=held;val pending=linkedSetOf<Long>();var count=0
        try{
            synchronized(this){if(closed || !held.active())return;activeKey=code;pending.add(key(code,0))}
            while(!held.waitForRelease(100))synchronized(this){
                if(closed)return;pending.add(key(code,0,++count))
            }
        }finally{
            try{synchronized(this){if(!closed && activeKey==code)pending.add(key(code,1))}}
            finally{activeKey=null;held.release();if(heldSelect===held)heldSelect=null}
        }
        if(!closed)acknowledged(pending)
    }
    override fun press(command:Int,hold:Boolean){
        val (code,long)=MiLinkWire.command(command,hold)
        pressKey(code,long)
    }
    private fun pressKey(code:Int,long:Boolean){
        if(long){holdKey(code,RemoteHold(1000));return}
        val ids=linkedSetOf<Long>()
        try{synchronized(this){activeKey=code;ids.add(key(code,0))};Thread.sleep(25)}
        finally{synchronized(this){if(!closed && activeKey==code)ids.add(key(code,1));activeKey=null}}
        if(!closed)acknowledged(ids)
    }
    // Android's speaker mute toggle. KEYCODE_MUTE (91) is microphone mute.
    override fun toggleMute(){pressKey(164,false)}
    override fun holdSelect(held:RemoteHold){holdKey(23,held)}
    @Synchronized override fun close(){
        heldSelect?.release();heldSelect=null
        activeKey?.let{try{if(!closed)key(it,1)}catch(_:Exception){}}
        activeKey=null;closed=true;try{socket.close()}catch(_:Exception){}
    }
}
