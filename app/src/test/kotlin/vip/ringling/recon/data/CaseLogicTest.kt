package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vip.ringling.recon.domain.CaseChanges
import vip.ringling.recon.domain.CaseLogic
import vip.ringling.recon.domain.CaseStatus
import vip.ringling.recon.domain.CaseTarget
import vip.ringling.recon.domain.CaseTargetState
import vip.ringling.recon.domain.ChangeKind
import vip.ringling.recon.domain.EdgeKind
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ScanSnapshot
import vip.ringling.recon.domain.Severity

class CaseLogicTest {
    private fun t(type: InputType, v: String) = CaseTarget(type, v, 0)
    private fun snap(id: String, type: InputType, v: String, ts: Long, risk: Int, exp: Int = 0, info: Int = 0, ids: List<String> = emptyList()) = ScanSnapshot(id, type, v, ts, risk, exp, info, ids)
    private fun state(target: CaseTarget, vararg s: ScanSnapshot) = CaseTargetState(target, s.maxByOrNull { it.ts }, s.toList())

    @Test fun sameHandleEdgesAndNoLinkYet() {
        val targets = listOf(t(InputType.EMAIL, "test.user@example.com"), t(InputType.USERNAME, "testuser"), t(InputType.PHONE, "5555550123"))
        val e = CaseLogic.edges(targets)
        assertEquals(EdgeKind.SAME_HANDLE, e.single { it.a == 0 && it.b == 1 }.kind)
        assertEquals(EdgeKind.NONE, e.single { it.b == 2 }.kind)
        assertEquals(2, e.size)
        val tagged = CaseLogic.edges(listOf(t(InputType.EMAIL, "jane.doe+shop@x.com"), t(InputType.EMAIL, "jane.doe@y.org")))
        assertEquals(EdgeKind.SAME_HANDLE, tagged.single().kind, "+tag is ignored")
        assertEquals(EdgeKind.SAME_DOMAIN, CaseLogic.edges(listOf(t(InputType.EMAIL, "a@acme.io"), t(InputType.DOMAIN, "acme.io"))).single().kind)
        assertEquals(emptyList(), CaseLogic.edges(listOf(t(InputType.EMAIL, "a@b.com"))))
    }

    @Test fun statusExposureAndAreaCases() {
        val e = state(t(InputType.EMAIL, "a@b.com"), snap("1", InputType.EMAIL, "a@b.com", 1, 100, 12, 10))
        val u = state(t(InputType.USERNAME, "ab"), snap("2", InputType.USERNAME, "ab", 1, 10, 1, 4))
        assertEquals(100, CaseLogic.exposure(listOf(e, u))); assertEquals(CaseStatus.CRITICAL, CaseLogic.status(listOf(e, u))); assertEquals(13, CaseLogic.exposedTotal(listOf(e, u)))
        assertEquals(CaseStatus.CLEAN, CaseLogic.status(listOf(state(t(InputType.EMAIL, "c@d.com"), snap("3", InputType.EMAIL, "c@d.com", 1, 0)))))
        val zip = state(t(InputType.ZIP, "83338"), snap("4", InputType.ZIP, "83338", 1, 0, 0, 68))
        assertEquals(CaseStatus.AREA, CaseLogic.status(listOf(zip, zip))); assertEquals(null, CaseLogic.exposure(listOf(zip)))
        assertEquals(CaseStatus.UNSCANNED, CaseLogic.status(listOf(CaseTargetState(t(InputType.EMAIL, "n@n.com"), null, emptyList()))))
    }

    @Test fun pivotsComeFromFindingsAndSkipWhatTheCaseAlreadyHas() {
        val email = t(InputType.EMAIL, "jane@x.com")
        val f = Finding("CODE-github-emails", ModuleId.CODE, "Commit e-mails", "d", Severity.EXPOSED, pivots = listOf(Pivot("Breach check", "pal@work.io"), Pivot("Breach check", "JANE@x.com")))
        val p = CaseLogic.pivots(listOf(email), mapOf(email.key to listOf(f)))
        assertEquals(listOf("jane", "pal@work.io"), p.map { it.query }, "handle suggestion first; the case's own email is skipped")
        assertEquals("Handle matches the email name", p[0].title)
        assertEquals(listOf("pal@work.io"), CaseLogic.pivots(listOf(email, t(InputType.USERNAME, "jane")), mapOf(email.key to listOf(f))).map { it.query }, "no handle suggestion when the username is already a target")
    }

    @Test fun changeEventsDiffAgainstTheSameTargetsPreviousScan() {
        val s1 = snap("a", InputType.EMAIL, "a@b.com", 10, 34, 3, 8, listOf("x", "y")); val s2 = snap("b", InputType.EMAIL, "a@b.com", 20, 100, 12, 8, listOf("x", "y", "z"))
        val s3 = snap("c", InputType.EMAIL, "a@b.com", 30, 100, 12, 10, listOf("x", "y", "z", "w")); val s4 = snap("d", InputType.EMAIL, "a@b.com", 40, 100, 12, 10, listOf("x", "y", "z", "w"))
        val other = snap("p", InputType.PHONE, "5555550123", 25, 8, 0, 2)
        val ev = CaseChanges.events(listOf(state(t(InputType.EMAIL, "a@b.com"), s1, s2, s3, s4), state(t(InputType.PHONE, "5555550123"), other)))
        assertEquals(listOf("d", "c", "p", "b", "a"), ev.map { it.snapshot.id }, "newest first")
        val byId = ev.associateBy { it.snapshot.id }
        assertEquals(ChangeKind.FIRST, byId.getValue("a").kind); assertEquals("First scan", byId.getValue("a").tag); assertEquals("Risk 34. 3 exposed, 8 info.", byId.getValue("a").summary)
        assertEquals(ChangeKind.WORSE, byId.getValue("b").kind); assertEquals("▲ 66", byId.getValue("b").tag)
        assertTrue("Risk 34 → 100." in byId.getValue("b").summary && "Exposed 3 → 12." in byId.getValue("b").summary && "1 new finding." in byId.getValue("b").summary, byId.getValue("b").summary)
        assertEquals(ChangeKind.INFO_MORE, byId.getValue("c").kind); assertEquals("+2 info", byId.getValue("c").tag); assertTrue("Info findings 8 → 10." in byId.getValue("c").summary)
        assertEquals(ChangeKind.SAME, byId.getValue("d").kind); assertEquals(ChangeKind.FIRST, byId.getValue("p").kind, "the phone is diffed only against phone scans")
        val better = CaseChanges.describe(s2, snap("e", InputType.EMAIL, "a@b.com", 50, 40, 4, 8)); assertEquals(ChangeKind.BETTER, better.kind); assertEquals("▼ 60", better.tag)
    }

    @Test fun areaChangesAreCountsAndTheCaseSeriesFollowsTheWorstTarget() {
        val z1 = snap("1", InputType.ZIP, "83338", 1, 0, 0, 66); val z2 = snap("2", InputType.ZIP, "83338", 2, 0, 0, 68)
        val a = CaseChanges.describe(z1, z2); assertEquals("+2 records", a.tag); assertEquals("Records 66 → 68.", a.summary); assertTrue(!a.worse && !a.better)
        assertEquals(ChangeKind.FIRST, CaseChanges.describe(null, z1).kind)
        val e = state(t(InputType.EMAIL, "a@b.com"), snap("a", InputType.EMAIL, "a@b.com", 10, 34), snap("b", InputType.EMAIL, "a@b.com", 30, 100))
        val u = state(t(InputType.USERNAME, "ab"), snap("c", InputType.USERNAME, "ab", 20, 10))
        val series = CaseChanges.exposureSeries(listOf(e, u, state(t(InputType.ZIP, "83338"), z1, z2)))
        assertEquals(listOf(10L to 34, 20L to 34, 30L to 100), series, "areas never enter the series; the worst target at each moment wins")
        assertEquals(66, CaseChanges.sinceFirst(series)); assertEquals(null, CaseChanges.sinceFirst(series.take(1)))
    }
}
