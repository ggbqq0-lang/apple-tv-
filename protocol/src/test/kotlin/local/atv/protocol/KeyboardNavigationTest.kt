package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class KeyboardNavigationTest {
    private fun target()=LiveTextPolicy().apply{arm(ByteArray(16){1})}
    @Test fun lastCommittedEditIsVerifiedBeforeFirstDirectionAndSentOnlyOnce(){
        val order=mutableListOf<String>()
        val navigation=KeyboardNavigation("最后A",target())
        repeat(2){navigation.run({true},{text,policy->
            policy.check(ByteArray(16){1});assertEquals("最后A",text);order+="text"
            SendResult(true,3,7)
        },{order+="direction"})}
        assertEquals(listOf("text","direction","direction"),order)
    }
    @Test fun enteringWithoutEditsOrUsingManualModeDoesNotClearTV(){
        var controls=0
        KeyboardNavigation(null,null).run({true},{_,_->fail("must not write an empty draft");SendResult(true,0,0)},{controls++})
        assertEquals(1,controls)
    }
    @Test fun mismatchBlocksControlAndFurtherActionsWithoutReplayingText(){
        var writes=0;var controls=0
        val navigation=KeyboardNavigation("draft",target())
        repeat(2){assertThrows(ProtocolFailure::class.java){navigation.run({true},{_,_->writes++;SendResult(false,5,5)},{controls++})}}
        assertEquals(1,writes);assertEquals(0,controls)
    }
    @Test fun changedTargetStopsBeforeWritingOrMoving(){
        var writes=0;var controls=0
        val navigation=KeyboardNavigation("draft",target())
        assertThrows(ProtocolFailure::class.java){navigation.run({true},{_,policy->
            policy.check(ByteArray(16){2});writes++;SendResult(true,5,5)
        },{controls++})}
        assertEquals(0,writes);assertEquals(0,controls)
    }
    @Test fun writeOutcomeUnknownDoesNotRetryOrSendFollowingConfirm(){
        var writes=0;var controls=0
        val navigation=KeyboardNavigation("draft",target())
        repeat(2){assertThrows(ProtocolFailure::class.java){navigation.run({true},{_,_->writes++;throw ProtocolFailure("WRITE_OUTCOME_UNKNOWN")},{controls++})}}
        assertEquals(1,writes);assertEquals(0,controls)
    }
    @Test fun exitOrConnectionChangeCancelsQueuedAndInFlightControl(){
        var writes=0;var controls=0
        val cancelled=KeyboardNavigation("draft",target());cancelled.cancel()
        cancelled.run({true},{_,_->writes++;SendResult(true,5,5)},{controls++})
        KeyboardNavigation("draft",target()).run({false},{_,_->writes++;SendResult(true,5,5)},{controls++})
        val inFlight=KeyboardNavigation("draft",target())
        inFlight.run({true},{_,_->writes++;inFlight.cancel();SendResult(true,5,5)},{controls++})
        assertEquals(1,writes);assertEquals(0,controls)
    }
}
