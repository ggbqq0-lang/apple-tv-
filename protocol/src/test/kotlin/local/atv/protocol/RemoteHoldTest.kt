package local.atv.protocol

import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class RemoteHoldTest {
    @Test fun releasedBeforeStartingNeverBecomesActive(){val held=RemoteHold();held.release();assertFalse(held.active());held.awaitRelease()}
    @Test fun fingerUpWakesWaitingWorker(){
        val held=RemoteHold();val worker=thread{held.awaitRelease()}
        held.release();worker.join(500);assertFalse(worker.isAlive);assertFalse(held.active())
    }
    @Test fun forgottenHoldHasHardDeadline(){val held=RemoteHold(30);held.awaitRelease();assertFalse(held.active())}
}
