package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import vip.ringling.recon.data.sources.domain.LookalikeGenerator
import vip.ringling.recon.data.sources.domain.LookalikeLookup
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

class LookalikeTest {
    @Test fun generatesTypoHomoglyphAndTldVariantsWithoutTheOriginal() {
        val v = LookalikeGenerator.generate("example.com")
        assertTrue("exmple.com" in v && "examlpe.com" in v && "exxample.com" in v && "examp1e.com" in v && "example.net" in v && "ex-ample.com" in v)
        assertTrue("example.com" !in v && v.size <= 150 && v.toSet().size == v.size)
        assertTrue(v.all { it.endsWith(".com") || it.substringAfterLast('.') in setOf("net", "org", "co", "io", "app", "xyz", "info", "biz", "online") })
    }

    @Test fun keepsTheRegistrablePartAndHandlesTwoPartSuffixes() {
        assertEquals("example" to "com", LookalikeGenerator.split("login.mail.example.com"))
        assertEquals("shop" to "co.uk", LookalikeGenerator.split("www.shop.co.uk"))
        assertEquals(null, LookalikeGenerator.split("localhost"))
        assertTrue(LookalikeGenerator.generate("shop.co.uk").any { it == "shp.co.uk" })
        assertTrue(LookalikeGenerator.generate("a.com").none { it.startsWith(".") || it.startsWith("-") })
    }

    @Test fun reportsOnlyVariantsThatResolve() = runTest {
        val (srv, _) = server("/doh" to { req ->
            val name = req.requestUrl!!.queryParameter("name")
            if (name == "exmple.com") json("""{"Status":0,"Answer":[{"name":"exmple.com.","type":1,"TTL":60,"data":"203.0.113.7"}]}""") else json("""{"Status":3}""")
        })
        try {
            val out = LookalikeLookup(vip.ringling.recon.data.net.Http(), srv.endpoints()).runPaid(LookalikeLookup.ACTION, ScanInput("example.com", InputType.DOMAIN))
            val f = out.single()
            assertEquals("DOMAIN-lookalikes", f.id); assertEquals(Severity.INFO, f.severity)
            assertEquals("1 look-alike domain resolve", f.title.replace("domains", "domain").replace("resolves", "resolve"))
            assertEquals(listOf("exmple.com"), f.facts.map { it.label }); assertEquals("203.0.113.7", f.facts.single().value)
            assertEquals(listOf("exmple.com"), f.pivots.map { it.query })
            assertTrue(LookalikeLookup(vip.ringling.recon.data.net.Http(), srv.endpoints()).runPaid("other", ScanInput("example.com", InputType.DOMAIN)).isEmpty())
        } finally { srv.shutdown() }
    }

    @Test fun mostlyFailingLookupsAreAnErrorNotAFalseAllClear() = runTest {
        val (srv, _) = server("/doh" to { json("{}", 500) })
        try {
            val f = LookalikeLookup(vip.ringling.recon.data.net.Http(), srv.endpoints()).runPaid(LookalikeLookup.ACTION, ScanInput("example.com", InputType.DOMAIN)).single()
            assertEquals("DOMAIN-lookalikes-error", f.id)
        } finally { srv.shutdown() }
    }
}
