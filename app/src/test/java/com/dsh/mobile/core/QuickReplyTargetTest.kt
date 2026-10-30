package com.dsh.mobile.core
import org.junit.Assert.*
import org.junit.Test
class QuickReplyTargetTest {
    private fun token(profile:String="deviceA",selection:String="session1",origin:String="https://fixture.invalid",title:String="a")="""{"profile":"$profile","context":{"origin":"$origin","path":"/","selection":"$selection","title":"$title"}}"""
    @Test fun titleRenameDoesNotRetargetDraft(){assertTrue(QuickReplyTarget.same(token(),token(title="renamed")))}
    @Test fun switchingAccountOrSessionOrOriginRejectsSend(){assertFalse(QuickReplyTarget.same(token(),token(profile="B")));assertFalse(QuickReplyTarget.same(token(),token(selection="session2")));assertFalse(QuickReplyTarget.same(token(),token(origin="https://other.invalid")))}
    @Test fun runningSessionMustMatchSelectedConversation(){assertTrue(QuickReplyTarget.matchesSession(token(selection="session:run1"),"run1"));assertFalse(QuickReplyTarget.matchesSession(token(),"run1"))}
    @Test fun missingTargetNeverPasses(){assertFalse(QuickReplyTarget.same("",""));assertFalse(QuickReplyTarget.same("{}","{}"));assertFalse(QuickReplyTarget.same(token(selection=""),token(selection="")))}
}
