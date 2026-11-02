package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class KeyboardFieldTrackerTest {
    private fun field(id:Int,text:String?=null)=TextState(true,ByteArray(16){id.toByte()},text)
    @Test fun newFieldImportsItsOwnContentAndNeverRestoresThePreviousDraft(){
        val tracker=KeyboardFieldTracker()
        assertEquals("first",tracker.observe(field(1,"first"))!!.text)
        assertEquals("second中文",tracker.observe(field(2,"second中文"))!!.text)
        assertEquals("",tracker.observe(field(3,""))!!.text)
        assertEquals("fresh first",tracker.observe(field(1,"fresh first"))!!.text)
    }
    @Test fun sameFieldDoesNotOverwriteUnsentLocalEditsOnEveryPoll(){
        val tracker=KeyboardFieldTracker();tracker.observe(field(1,"server"))
        assertNull(tracker.observe(field(1,"older server response")))
    }
    @Test fun unreadableOrClosedFieldClearsTheOldContent(){
        val tracker=KeyboardFieldTracker();tracker.observe(field(1,"old"))
        assertEquals("",tracker.observe(field(2,null))!!.text)
        val closed=tracker.observe(TextState(false))!!
        assertFalse(closed.focused);assertEquals("",closed.text);assertNull(tracker.snapshot())
        assertNull(tracker.observe(TextState(false)))
        assertEquals("reopened",tracker.observe(field(2,"reopened"))!!.text)
    }
    @Test fun reenteringOrReconnectingReadsTheCurrentFieldAgain(){
        val tracker=KeyboardFieldTracker();tracker.observe(field(1,"old"));tracker.reset()
        assertNull(tracker.snapshot());assertEquals("current",tracker.observe(field(1,"current"))!!.text)
    }
    @Test fun aQueuedSnapshotCannotWriteIntoTheNextField(){
        val tracker=KeyboardFieldTracker();tracker.observe(field(1,"old"));val queued=tracker.snapshot()!!
        tracker.observe(field(2,"new"));val current=tracker.snapshot()!!
        assertThrows(ProtocolFailure::class.java){queued.check(field(2).sessionId)}
        current.check(field(2).sessionId)
        assertThrows(ProtocolFailure::class.java){current.check(field(1).sessionId)}
    }
    @Test fun trackerCopiesTheIdentityAndDoesNotTreatMissingIdentityAsAnEditableField(){
        val tracker=KeyboardFieldTracker();val state=field(1,"original");tracker.observe(state)
        state.sessionId!!.fill(2)
        assertNull(tracker.observe(field(1,"same field")))
        val invalid=tracker.observe(TextState(true,ByteArray(3),"must not import"))!!
        assertFalse(invalid.focused);assertEquals("",invalid.text);assertNull(tracker.snapshot())
    }
    @Test fun fullReportedContextIncludesTextAfterTheCursor(){
        val archive=Plist.encode(mapOf("\$top" to mapOf("documentState" to Uid(1)),"\$objects" to listOf("\$null",mapOf("docSt" to Uid(2)),
            mapOf("contextBeforeInput" to Uid(3),"contextAfterInput" to Uid(4)),"before中","after文")))
        assertEquals("before中after文",Rti.context(archive))
    }
}
