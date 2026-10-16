package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class RemoteInputBufferTest {
    @Test fun hundredsOfMovesKeepOnlyLatestStep(){
        val queue=RemoteInputBuffer<Int>();queue.beginGesture(1)
        repeat(500){queue.offerMovement(1,it,1000)}
        assertEquals(499,queue.poll(1000)?.value);assertNull(queue.poll(1000))
    }
    @Test fun fingerUpDropsQueuedAndAlreadyTakenMovement(){
        val queue=RemoteInputBuffer<Int>();queue.beginGesture(1)
        queue.offerMovement(1,4,1000);val taken=queue.poll(1000)!!
        queue.offerMovement(1,4,1000);queue.endGesture(1)
        assertNull(queue.poll(1000));assertFalse(queue.valid(taken,1000))
        assertFalse(queue.offerMovement(1,4,1000))
    }
    @Test fun oldGestureCannotClearNewGesture(){
        val queue=RemoteInputBuffer<Int>();queue.beginGesture(1);queue.beginGesture(2)
        queue.offerMovement(2,3,1000);queue.endGesture(1)
        assertEquals(3,queue.poll(1000)?.value)
    }
    @Test fun clickBurstIsBoundedAndExpiredInputsCannotRun(){
        val queue=RemoteInputBuffer<Int>(maxClicks=3,maxAgeMillis=200)
        repeat(3){assertTrue(queue.offerClick(it,1000))};assertFalse(queue.offerClick(4,1000))
        val taken=queue.poll(1000)!!;assertFalse(queue.valid(taken,1201));assertNull(queue.poll(1201))
    }
    @Test fun movementExpiresDuringNetworkDelay(){
        val queue=RemoteInputBuffer<Int>();queue.beginGesture(1);queue.offerMovement(1,3,1000)
        val taken=queue.poll(1000)!!;assertFalse(queue.valid(taken,1151))
    }
    @Test fun disconnectClearsAllTypesOfInput(){
        val queue=RemoteInputBuffer<Int>();queue.beginGesture(1)
        queue.offerMovement(1,4,1000);queue.offerClick(6,1000);queue.clear()
        assertNull(queue.poll(1000));assertFalse(queue.offerMovement(1,4,1000))
    }
    @Test fun swipeNeedsDistanceAndMapsAxesWithoutInertia(){
        val swipe=SwipeDirectionTracker(100f,100f,24f)
        assertNull(swipe.move(110f,100f));assertEquals(4,swipe.move(125f,100f))
        assertNull(swipe.move(125f,100f));assertEquals(3,swipe.move(99f,100f))
        assertEquals(1,swipe.move(99f,75f));assertEquals(2,swipe.move(99f,100f))
    }
    @Test fun diagonalDragHasNoDeadZoneBetweenRecognitionAndFirstStep(){
        val gesture=TouchGestureClassifier(0f,0f,18f)
        val swipe=SwipeDirectionTracker(0f,0f,18f)
        assertTrue(gesture.move(14f,14f));assertNotNull(swipe.move(14f,14f))
    }
}
