package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class TouchGestureClassifierTest {
    @Test fun gentleTapToleratesContactDrift(){
        val gesture=TouchGestureClassifier(100f,100f,18f)
        assertFalse(gesture.move(109f,108f))
        assertTrue(gesture.finishIsTap(111f,105f))
    }
    @Test fun liftOnlyJumpStillCountsAsTapWithinBound(){
        val gesture=TouchGestureClassifier(100f,100f,18f)
        assertTrue(gesture.finishIsTap(128f,100f))
        assertFalse(gesture.finishIsTap(137f,100f))
    }
    @Test fun realSwipeReturningToOriginNeverSelects(){
        val gesture=TouchGestureClassifier(100f,100f,18f)
        assertTrue(gesture.move(125f,100f))
        assertTrue(gesture.move(100f,100f))
        assertFalse(gesture.finishIsTap(100f,100f))
    }
    @Test fun quickSwipeWithOnlyDownAndUpDoesNotSelect(){
        assertFalse(TouchGestureClassifier(100f,100f,18f).finishIsTap(200f,100f))
    }
    @Test fun clickpadMapsCenterAndFourRingDirections(){
        assertEquals(6,TouchGestureClassifier.tapCommand(500,500))
        assertEquals(6,TouchGestureClassifier.tapCommand(700,500))
        assertEquals(1,TouchGestureClassifier.tapCommand(500,50))
        assertEquals(2,TouchGestureClassifier.tapCommand(500,950))
        assertEquals(3,TouchGestureClassifier.tapCommand(50,500))
        assertEquals(4,TouchGestureClassifier.tapCommand(950,500))
    }
}
