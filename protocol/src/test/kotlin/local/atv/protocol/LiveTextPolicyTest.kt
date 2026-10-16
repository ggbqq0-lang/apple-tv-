package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class LiveTextPolicyTest {
    @Test fun pendingEditSnapshotKeepsOnlyTheOriginalAuthorizedTarget(){
        val policy=LiveTextPolicy();assertNull(policy.snapshot())
        policy.arm(ByteArray(16){1});val snapshot=policy.snapshot()!!
        policy.stop();policy.arm(ByteArray(16){2})
        snapshot.check(ByteArray(16){1})
        assertThrows(ProtocolFailure::class.java){snapshot.check(ByteArray(16){2})}
    }
    @Test fun defaultEntryWaitsForFocusedTarget(){
        val policy=LiveTextPolicy();val request=policy.requestBinding();assertFalse(policy.active())
        assertFalse(policy.bind(request,null));assertTrue(policy.bind(request,ByteArray(16){1}));assertTrue(policy.active())
    }
    @Test fun leavingPageInvalidatesInFlightDefaultBinding(){
        val policy=LiveTextPolicy();val request=policy.requestBinding();policy.stop()
        assertFalse(policy.bind(request,ByteArray(16)));assertFalse(policy.active())
    }
    @Test fun oldBindingCannotArmAfterReentryAndActiveBindingCannotRetarget(){
        val policy=LiveTextPolicy();val old=policy.requestBinding();val current=policy.requestBinding()
        assertFalse(policy.bind(old,ByteArray(16)));assertTrue(policy.bind(current,ByteArray(16){1}))
        assertFalse(policy.bind(current,ByteArray(16){2}))
        assertThrows(ProtocolFailure::class.java){policy.check(ByteArray(16){2})}
    }
    @Test fun defaultDraftDoesNotRestoreOldOrImportPartialTVContext(){
        assertEquals("",LiveTextPolicy.draftForEntry("old deleted text",""))
        assertEquals("",LiveTextPolicy.draftForEntry("draft",null))
        assertEquals("",LiveTextPolicy.draftForEntry("","partial context before cursor"))
        assertEquals("same",LiveTextPolicy.draftForEntry("same","same"))
    }
    @Test fun cannotArmWithoutFocusedSession(){assertThrows(ProtocolFailure::class.java){LiveTextPolicy().arm(null)}}
    @Test fun changingFieldsRevokesLiveWrites(){
        val policy=LiveTextPolicy();val first=ByteArray(16){1};policy.arm(first)
        policy.check(first.copyOf())
        assertThrows(ProtocolFailure::class.java){policy.check(ByteArray(16){2})}
        assertThrows(ProtocolFailure::class.java){policy.check(null)}
    }
    @Test fun disablingAndRearmingDoesNotAuthorizeOldField(){
        val policy=LiveTextPolicy();val first=ByteArray(16){1};val second=ByteArray(16){2}
        policy.arm(first);policy.stop();assertFalse(policy.active())
        assertThrows(ProtocolFailure::class.java){policy.check(first)}
        policy.arm(second);policy.check(second)
        assertThrows(ProtocolFailure::class.java){policy.check(first)}
    }
}
