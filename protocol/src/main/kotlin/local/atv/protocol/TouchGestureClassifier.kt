package local.atv.protocol

/** Distinguish intentional sliding from a tap's contact jitter. No Android dependency. */
class TouchGestureClassifier(private val startX: Float, private val startY: Float, private val dragSlop: Float) {
    private var dragging=false
    init { require(dragSlop.isFinite() && dragSlop>0) }
    private fun distanceSquared(x:Float,y:Float):Float {val dx=x-startX;val dy=y-startY;return dx*dx+dy*dy}
    fun move(x:Float,y:Float):Boolean {
        if(distanceSquared(x,y)>dragSlop*dragSlop)dragging=true
        return dragging
    }
    fun finishIsTap(x:Float,y:Float):Boolean {
        // A single jump on finger lift is less reliable than pressed move samples.
        // Still reject a real fast swipe that has only down/up coordinates.
        return !dragging && distanceSquared(x,y)<=4*dragSlop*dragSlop
    }

    companion object {
        /** A normalized 1000 x 1000 circular clickpad, with a central Select area. */
        fun tapCommand(x:Int,y:Int):Int {
            val dx=x-500;val dy=y-500
            if(dx*dx+dy*dy <= 335*335)return 6
            return if(kotlin.math.abs(dx)>kotlin.math.abs(dy)){if(dx<0)3 else 4}else{if(dy<0)1 else 2}
        }
    }
}
