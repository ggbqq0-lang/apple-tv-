package local.atv.protocol

/** Bounded clicks and a single replaceable movement, never an unbounded command backlog. */
class RemoteInputBuffer<T>(private val maxClicks:Int=4,private val maxAgeMillis:Long=700) {
    data class Entry<T>(val value:T,val gesture:Long?,val expiresAt:Long)
    private val clicks=ArrayDeque<Entry<T>>()
    private var movement:Entry<T>?=null
    private var activeGesture:Long?=null
    @Synchronized fun beginGesture(id:Long){activeGesture=id;movement=null}
    @Synchronized fun endGesture(id:Long){if(activeGesture==id){activeGesture=null;movement=null}}
    @Synchronized fun offerClick(value:T,now:Long):Boolean {
        clicks.removeAll{it.expiresAt<now}
        if(clicks.size>=maxClicks)return false
        clicks.addLast(Entry(value,null,now+maxAgeMillis));return true
    }
    @Synchronized fun offerMovement(id:Long,value:T,now:Long,maxAgeMillis:Long=150):Boolean {
        require(maxAgeMillis in 1..700)
        if(activeGesture!=id)return false
        movement=Entry(value,id,now+maxAgeMillis);return true
    }
    @Synchronized fun poll(now:Long):Entry<T>? {
        while(clicks.isNotEmpty()){val next=clicks.removeFirst();if(valid(next,now))return next}
        val next=movement;movement=null
        return next?.takeIf{valid(it,now)}
    }
    @Synchronized fun valid(entry:Entry<T>,now:Long)=entry.expiresAt>=now && (entry.gesture==null || entry.gesture==activeGesture)
    @Synchronized fun clear(){clicks.clear();movement=null;activeGesture=null}
}

/** Each distance threshold yields at most one short direction press, with no inertia. */
class SwipeDirectionTracker(startX:Float,startY:Float,private val step:Float){
    private var x=startX;private var y=startY
    init{require(step.isFinite() && step>0)}
    fun move(nextX:Float,nextY:Float):Int? {
        val dx=nextX-x;val dy=nextY-y
        if(dx*dx+dy*dy<step*step)return null
        x=nextX;y=nextY
        return if(kotlin.math.abs(dx)>kotlin.math.abs(dy)){if(dx<0)3 else 4}else{if(dy<0)1 else 2}
    }
}
