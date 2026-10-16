package local.atv.protocol

import java.io.ByteArrayOutputStream

data class Uid(val value: Int)

/** Bounded binary plist codec: only the types used by RTI keyed archives. */
object Plist {
    private const val LIMIT=1024*1024
    private fun width(n: Long) = if(n<256)1 else if(n<65536)2 else if(n<=0xffffffffL)4 else 8
    private fun ByteArrayOutputStream.number(n: Long, size: Int) { for(i in size-1 downTo 0)write((n ushr (i*8)).toInt() and 255) }
    fun encode(root: Any?): ByteArray {
        val objects=mutableListOf<Any?>()
        class ArrayRefs(val refs: List<Int>)
        class DictRefs(val keys: List<Int>,val values: List<Int>)
        fun add(v: Any?, depth: Int): Int {
            ensure(depth<64 && objects.size<16384,"PLIST_COMPLEXITY")
            val index=objects.size; objects.add(null)
            objects[index]=when(v) {
                is Map<*,*> -> DictRefs(v.keys.map {add(it,depth+1)},v.values.map {add(it,depth+1)})
                is List<*> -> ArrayRefs(v.map {add(it,depth+1)})
                else -> v
            }; return index
        }
        val top=add(root,0); val refWidth=width(objects.size.toLong())
        val out=ByteArrayOutputStream(); out.write(utf8("bplist00")); val offsets=mutableListOf<Int>()
        fun length(tag: Int, n: Int) { if(n<15)out.write(tag+n) else {out.write(tag+15); val w=width(n.toLong());out.write(0x10+Integer.numberOfTrailingZeros(w));out.number(n.toLong(),w)} }
        objects.forEach {v ->
            offsets.add(out.size())
            when(v) {
                null -> out.write(0)
                is Boolean -> out.write(if(v)9 else 8)
                is Uid -> { val w=width(v.value.toLong());out.write(0x80+w-1);out.number(v.value.toLong(),w) }
                is Number -> {val n=v.toLong();val w=if(n<0)8 else width(n);out.write(0x10+Integer.numberOfTrailingZeros(w));out.number(n,w)}
                is ByteArray -> {length(0x40,v.size);out.write(v)}
                is String -> {val ascii=v.all {it.code<128};val b=v.toByteArray(if(ascii)Charsets.US_ASCII else Charsets.UTF_16BE);length(if(ascii)0x50 else 0x60,if(ascii)b.size else b.size/2);out.write(b)}
                is ArrayRefs -> {length(0xa0,v.refs.size);v.refs.forEach{out.number(it.toLong(),refWidth)}}
                is DictRefs -> {length(0xd0,v.keys.size);(v.keys+v.values).forEach{out.number(it.toLong(),refWidth)}}
                else -> throw ProtocolFailure("PLIST_TYPE")
            }; ensure(out.size()<=LIMIT,"PLIST_SIZE")
        }
        val table=out.size(); val offsetWidth=width(table.toLong())
        offsets.forEach{out.number(it.toLong(),offsetWidth)}
        out.write(ByteArray(6));out.write(offsetWidth);out.write(refWidth)
        out.number(objects.size.toLong(),8);out.number(top.toLong(),8);out.number(table.toLong(),8)
        return out.toByteArray()
    }
    fun decode(data: ByteArray): Any? {
        ensure(data.size in 40..LIMIT && data.copyOfRange(0,8).contentEquals(utf8("bplist00")),"PLIST_HEADER")
        fun number(pos: Int, size: Int): Long {
            ensure(size in 1..8 && pos>=0 && size<=data.size-pos,"PLIST_TRUNCATED")
            var n=0L;repeat(size){n=(n shl 8) or (data[pos+it].toLong() and 255)};return n
        }
        val end=data.size-32;val ow=number(end+6,1).toInt();val rw=number(end+7,1).toInt()
        val count=number(end+8,8);val top=number(end+16,8);val table=number(end+24,8)
        ensure(count in 1..16384 && top in 0 until count && table in 8..end.toLong() && ow in 1..8 && rw in 1..8 && count*ow <= end-table,"PLIST_TRAILER")
        val cache=mutableMapOf<Int,Any?>();val active=mutableSetOf<Int>()
        fun item(index: Int, depth: Int): Any? {
            ensure(depth<64 && index in 0 until count.toInt(),"PLIST_REFERENCE")
            if(cache.containsKey(index))return cache[index]
            ensure(active.add(index),"PLIST_CYCLE")
            val offset=number(table.toInt()+index*ow,ow);ensure(offset in 8 until table,"PLIST_OFFSET")
            var p=offset.toInt();val tag=number(p++,1).toInt();val kind=tag ushr 4
            var size=tag and 15
            if(size==15 && kind in listOf(4,5,6,0xa,0xd)){
                val marker=number(p++,1).toInt();ensure(marker ushr 4==1 && marker and 15<=3,"PLIST_LENGTH")
                val w=1 shl (marker and 15);val length=number(p,w);p+=w
                ensure(length in 0..LIMIT.toLong(),"PLIST_LENGTH");size=length.toInt()
            }
            fun payload(n: Int): ByteArray {ensure(n>=0 && n<=table-p,"PLIST_TRUNCATED");return data.copyOfRange(p,p+n)}
            fun ref(pos: Int) = number(pos,rw).also {ensure(it in 0 until count,"PLIST_REFERENCE")}.toInt()
            val value: Any?=when(kind){
                0 -> when(tag){0->null;8->false;9->true;else->throw ProtocolFailure("PLIST_SIMPLE_TYPE")}
                1 -> {ensure(size<=3,"PLIST_INTEGER");number(p,1 shl size)}
                4 -> payload(size)
                5 -> payload(size).toString(Charsets.US_ASCII)
                6 -> payload(size*2).toString(Charsets.UTF_16BE)
                8 -> Uid(number(p,size+1).also{ensure(it in 0..16384,"PLIST_UID")}.toInt())
                0xa -> {payload(size*rw);List(size){item(ref(p+it*rw),depth+1)}}
                0xd -> {payload(size*rw*2);linkedMapOf<String,Any?>().apply{repeat(size){
                    val key=item(ref(p+it*rw),depth+1);ensure(key is String,"PLIST_MAP_KEY")
                    put(key as String,item(ref(p+(size+it)*rw),depth+1))}}}
                else -> throw ProtocolFailure("PLIST_TYPE_UNSUPPORTED")
            };active.remove(index);cache[index]=value;return value
        }
        return item(top.toInt(),0)
    }
}

object Rti {
    private fun clazz(name: String) = linkedMapOf("\$classname" to name,"\$classes" to listOf(name,"NSObject"))
    fun operation(uuid: ByteArray, text: String, clear: Boolean): ByteArray {
        ensure(uuid.size==16,"RTI_SESSION_UUID")
        val objects=if(clear)listOf("\$null",linkedMapOf("\$class" to Uid(7),"targetSessionUUID" to Uid(5),"keyboardOutput" to Uid(2),"textToAssert" to Uid(4)),
            mapOf("\$class" to Uid(3)),clazz("TIKeyboardOutput"),"",mapOf("NS.uuidbytes" to uuid,"\$class" to Uid(6)),clazz("NSUUID"),clazz("RTITextOperations"))
        else listOf("\$null",linkedMapOf("keyboardOutput" to Uid(2),"\$class" to Uid(7),"targetSessionUUID" to Uid(5)),
            mapOf("insertionText" to Uid(3),"\$class" to Uid(4)),text,clazz("TIKeyboardOutput"),mapOf("NS.uuidbytes" to uuid,"\$class" to Uid(6)),clazz("NSUUID"),clazz("RTITextOperations"))
        return Plist.encode(linkedMapOf("\$version" to 100000,"\$archiver" to "RTIKeyedArchiver","\$top" to mapOf("textOperations" to Uid(1)),"\$objects" to objects))
    }
    fun property(archive: ByteArray, vararg path: String): Any? {
        val root=Plist.decode(archive) as? Map<*,*> ?: throw ProtocolFailure("RTI_ARCHIVE")
        val objects=root["\$objects"] as? List<*> ?: throw ProtocolFailure("RTI_ARCHIVE")
        var value=root["\$top"]
        path.forEach {key ->
            value=(value as? Map<*,*>)?.get(key)
            repeat(4) {if(value is Uid) value=objects.getOrNull((value as Uid).value)}
        };return value
    }
    fun session(archive: ByteArray): ByteArray? {
        val value=property(archive,"sessionUUID")
        return when(value){is ByteArray->value;is Map<*,*>->value["NS.uuidbytes"] as? ByteArray;else->null}
    }
    fun context(archive: ByteArray): String? = property(archive,"documentState","docSt","contextBeforeInput") as? String
}
