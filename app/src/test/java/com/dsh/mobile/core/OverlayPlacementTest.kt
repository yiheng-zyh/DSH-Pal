package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class OverlayPlacementTest {
 @Test fun rightEdgeSurvivesRepeatedWidthChanges(){val a=OverlayPlacement.anchor(900,0,900);assertEquals(300,OverlayPlacement.coordinate(a,0,300));assertEquals(900,OverlayPlacement.coordinate(a,0,900))}
 @Test fun bottomEdgeSurvivesHeightChanges(){val a=OverlayPlacement.anchor(1800,24,1800);assertEquals(1200,OverlayPlacement.coordinate(a,24,1200));assertEquals(1800,OverlayPlacement.coordinate(a,24,1800))}
 @Test fun interiorAnchorDoesNotDriftAfterManyResizes(){val a=OverlayPlacement.anchor(360,0,900);repeat(100){assertEquals(120,OverlayPlacement.coordinate(a,0,300));assertEquals(360,OverlayPlacement.coordinate(a,0,900))}}
 @Test fun leftEdgeAndClippedBoundsRemainSafe(){assertEquals(0,OverlayPlacement.coordinate(0f,0,900));assertEquals(24,OverlayPlacement.coordinate(1f,24,0))}
 @Test fun dragReplacesThePreferredAnchor(){val a=OverlayPlacement.anchor(150,0,300);assertEquals(450,OverlayPlacement.coordinate(a,0,900))}
}
