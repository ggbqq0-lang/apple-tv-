package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test

class NetworkMuteTest {
    @Test fun zeroOrUnknownReadingNeverMeansMutedAndNeverRaisesVolume(){
        val mute=NetworkMute();var writes=0
        mute.observe(0.0);assertFalse(mute.muted);assertFalse(mute.available(0.0));assertFalse(mute.available(null))
        assertThrows(ProtocolFailure::class.java){mute.toggle({0.0}){writes++}}
        assertEquals(0,writes)
    }
    @Test fun knownVolumeCanMuteAndRestoreExactly(){
        val mute=NetworkMute();var level=.37;val targets=mutableListOf<Double>()
        assertTrue(mute.available(level));assertTrue(mute.toggle({level}){targets+=it;level=it})
        assertTrue(mute.muted);assertTrue(mute.available(0.0))
        assertFalse(mute.toggle({level}){targets+=it;level=it})
        assertEquals(listOf(0.0,.37),targets);assertEquals(.37,level,.001)
    }
    @Test fun rejectedAbsoluteVolumeDoesNotClaimMute(){
        val mute=NetworkMute()
        assertThrows(ProtocolFailure::class.java){mute.toggle({.4}){throw ProtocolFailure("MUTE_UNAVAILABLE")}}
        assertFalse(mute.muted);assertFalse(mute.available(.4))
    }
    @Test fun relativeOrExternalVolumeChangeResetsSavedMute(){
        val mute=NetworkMute();mute.toggle({.4}){}
        mute.observe(.5);assertFalse(mute.muted)
        mute.toggle({.5}){};mute.reset();assertFalse(mute.muted);assertFalse(mute.available(0.0))
        mute.toggle({.5}){};assertFalse(mute.available(null));mute.observe(null);assertFalse(mute.muted)
    }
}
