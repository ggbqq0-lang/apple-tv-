package local.atv.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object Opack {
    private const val LIMIT = 1024 * 1024
    fun pack(value: Any?): ByteArray = ByteArrayOutputStream().run {
        fun number(value: Long, size: Int) { repeat(size) { write((value ushr (8*it)).toInt() and 255) } }
        fun length(size: Int, inline: Int, extended: Int, bytesMode: Boolean = false) {
            if (size <= 32) write(inline + size)
            else { val width = if (size <= 255) 1 else if (size <= 65535) 2 else 4
                write(extended + (if (bytesMode && width == 4) 3 else width)); number(size.toLong(),width) }
        }
        fun item(v: Any?, depth: Int) {
            ensure(depth < 64, "OPACK_DEPTH")
            when(v) {
                null -> write(4)
                is Boolean -> write(if(v) 1 else 2)
                is String -> { val bytes = utf8(v); length(bytes.size,0x40,0x60); write(bytes) }
                is ByteArray -> { length(v.size,0x70,0x90,true); write(v) }
                is UUID -> { write(5); write(ByteBuffer.allocate(16).putLong(v.mostSignificantBits).putLong(v.leastSignificantBits).array()) }
                is Float, is Double -> { write(0x36); number(java.lang.Double.doubleToRawLongBits((v as Number).toDouble()),8) }
                is Number -> { val n=v.toLong(); when { n == -1L -> write(7); n in 0..39 -> write(n.toInt()+8)
                    else -> { val width = if(n in 0..255) 1 else if(n in 0..65535) 2 else if(n in 0..0xffffffffL) 4 else 8
                        write(0x30 + when(width){1->0;2->1;4->2;else->3}); number(n,width) } } }
                is Map<*,*> -> { write(0xe0 + minOf(v.size,15)); v.forEach { (k,value)->item(k,depth+1); item(value,depth+1) }; if(v.size>=15)write(3) }
                is List<*> -> { write(0xd0 + minOf(v.size,15)); v.forEach { item(it,depth+1) }; if(v.size>=15)write(3) }
                else -> throw ProtocolFailure("OPACK_UNSUPPORTED_TYPE")
            }; ensure(size() <= LIMIT, "FRAME_TOO_LARGE")
        }
        item(value,0); toByteArray()
    }
    fun unpack(data: ByteArray): Any? {
        ensure(data.size <= LIMIT, "FRAME_TOO_LARGE")
        var pos=0; val objects=mutableListOf<Any?>()
        fun bytes(size: Int): ByteArray { ensure(size>=0 && size<=data.size-pos,"OPACK_TRUNCATED"); return data.copyOfRange(pos,pos+size).also {pos+=size} }
        fun byte() = bytes(1)[0].toInt() and 255
        fun number(size: Int): Long { ensure(size in 1..8,"OPACK_INTEGER_SIZE"); return bytes(size).foldIndexed(0L) {i,a,v -> a or ((v.toLong() and 255) shl (8*i))} }
        fun size(width: Int): Int { val n=number(width); ensure(n in 0..LIMIT.toLong(),"OPACK_LENGTH"); return n.toInt() }
        fun item(depth: Int): Any? {
            ensure(depth < 64, "OPACK_DEPTH"); val start=pos; val tag=byte(); var add=true
            val value: Any? = when(tag) {
                1 -> true.also {add=false}; 2 -> false.also {add=false}; 4 -> null.also {add=false}
                5 -> ByteBuffer.wrap(bytes(16)).run {UUID(long,long)}
                6 -> number(8)
                7 -> (-1L).also {add=false}
                in 8..0x2f -> (tag-8).toLong().also {add=false}
                in 0x30..0x33 -> number(1 shl (tag-0x30))
                0x35 -> java.lang.Float.intBitsToFloat(number(4).toInt()).toDouble()
                0x36 -> java.lang.Double.longBitsToDouble(number(8))
                in 0x40..0x60 -> bytes(tag-0x40).toString(Charsets.UTF_8)
                in 0x61..0x64 -> bytes(size(tag-0x60)).toString(Charsets.UTF_8)
                in 0x70..0x90 -> bytes(tag-0x70)
                in 0x91..0x94 -> bytes(size(1 shl (tag-0x91)))
                in 0xa0..0xc4 -> { add=false; val i=if(tag<=0xc0)tag-0xa0 else size(tag-0xc0)
                    ensure(i in objects.indices,"OPACK_REFERENCE"); objects[i] }
                in 0xd0..0xdf -> {add=false; val n=tag and 15; val list=mutableListOf<Any?>()
                    if(n==15) { while(pos<data.size && data[pos]!=3.toByte())list.add(item(depth+1)); ensure(byte()==3,"OPACK_TERMINATOR") }
                    else repeat(n){list.add(item(depth+1))}; list }
                in 0xe0..0xef -> {add=false; val n=tag and 15; val map=linkedMapOf<String,Any?>()
                    fun pair() { val k=item(depth+1); ensure(k is String,"OPACK_MAP_KEY"); map[k as String]=item(depth+1) }
                    if(n==15) {while(pos<data.size && data[pos]!=3.toByte())pair(); ensure(byte()==3,"OPACK_TERMINATOR")}
                    else repeat(n){pair()}; map }
                else -> throw ProtocolFailure("OPACK_TAG_UNSUPPORTED")
            }
            if(add && pos-start>1 && objects.none { if(it is ByteArray && value is ByteArray)it.contentEquals(value) else it==value }) objects.add(value)
            return value
        }
        return item(0).also {ensure(pos==data.size,"OPACK_TRAILING_DATA")}
    }
}
