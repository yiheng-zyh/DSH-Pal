package com.dsh.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ConnectionSwitchTest {
    private val public=ConnectionProfile.create("Public",ConnectionProfile.Kind.TUNNEL,"https://public.example.org")
    private val mesh=ConnectionProfile.create("Mesh",ConnectionProfile.Kind.MESH,"https://desktop.example.ts.net")

    @Test fun allOrderedTransportPairsCommitOnlyAfterPreparationAndProbe()=runBlocking {
        val usb=ConnectionProfile.create("USB",ConnectionProfile.Kind.USB,"http://dsh-usb.localhost:18765")
        val lan=ConnectionProfile.create("LAN",ConnectionProfile.Kind.LAN,"http://192.168.1.2:8080")
        val relay=ConnectionProfile.create("Relay",ConnectionProfile.Kind.RELAY,"https://fixture.dsh-market.com")
        val custom=ConnectionProfile.create("Custom",ConnectionProfile.Kind.CUSTOM,"https://custom.example.org")
        val routes=listOf(public,relay,custom,mesh,usb,lan)
        for(from in routes)for(to in routes) {
            val events=mutableListOf<String>()
            var selected=from
            ConnectionSwitch.run(to,from,{events+="prepare";assertEquals(from,selected)},
                {events+="probe";assertEquals(from,selected);ProbeResult(ProbeOutcome.DSH_FOUND)},
                {events+="commit";selected=to})
            assertEquals(listOf("prepare","probe","commit"),events)
            assertEquals(to,selected)
        }
    }
    @Test fun relayFailureRetriesFourTimesAndLeavesCloudflareSelected()=runBlocking {
        val relay=ConnectionProfile.create("Relay",ConnectionProfile.Kind.RELAY,"https://fixture.dsh-market.com")
        var selected=public
        var probes=0
        val prepared=mutableListOf<ConnectionProfile>()
        try {
            ConnectionSwitch.run(relay,public,{prepared+=it},{probes++;ProbeResult(ProbeOutcome.NETWORK_ERROR)},{selected=relay})
            fail("must reject unavailable relay")
        }catch(expected:IllegalStateException){}
        assertEquals(4,probes);assertEquals(public,selected);assertEquals(listOf(relay,public),prepared)
    }
    @Test fun unreachableOrWrongServerRestoresOldTransportWithoutSelectingTarget()=runBlocking {
        for(outcome in listOf(ProbeOutcome.NETWORK_ERROR,ProbeOutcome.HTTP_ERROR,ProbeOutcome.UNKNOWN_PAGE)) {
            val prepared=mutableListOf<ConnectionProfile>()
            var committed=false
            try {
                ConnectionSwitch.run(public,mesh,{prepared+=it},{ProbeResult(outcome)},{committed=true})
                fail("must reject $outcome")
            }catch(expected:IllegalStateException){}
            assertFalse(committed)
            assertEquals(listOf(public,mesh),prepared)
        }
    }
    @Test fun cancelledTransitionRestoresPreviousTransportAndPropagatesCancellation()=runBlocking {
        val prepared=mutableListOf<ConnectionProfile>()
        try {
            ConnectionSwitch.run(public,mesh,{prepared+=it},{throw CancellationException("cancelled")},{fail("unexpected commit")})
            fail("must propagate cancellation")
        }catch(expected:CancellationException){}
        assertEquals(listOf(public,mesh),prepared)
    }
    @Test fun missingVpnConfigurationDoesNotProbeOrCommit()=runBlocking {
        var probed=false
        try {
            ConnectionSwitch.run(mesh,public,{if(it==mesh)error("VPN configuration required")},
                {probed=true;ProbeResult(ProbeOutcome.DSH_FOUND)},{fail("unexpected commit")})
            fail("must reject missing config")
        }catch(expected:IllegalStateException){}
        assertFalse(probed)
    }
}
