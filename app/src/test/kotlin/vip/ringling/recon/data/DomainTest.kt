package vip.ringling.recon.data

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.sources.domain.DnsRecord
import vip.ringling.recon.data.sources.domain.DohClient
import vip.ringling.recon.data.sources.domain.DomainAnalysis
import vip.ringling.recon.data.sources.domain.DomainSource
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

class DomainAnalysisTest {
    @Test fun unquotesChunkedTxt() {
        assertEquals("v=spf1 include:a ~all", DohClient.unquoteTxt("\"v=spf1 include:a ~all\""))
        assertEquals("abcdef", DohClient.unquoteTxt("\"abc\" \"def\""))
        assertEquals("say \"hi\"", DohClient.unquoteTxt("\"say \\\"hi\\\"\""))
    }

    @Test fun spfRules() {
        assertEquals(Severity.EXPOSED, DomainAnalysis.spf(emptyList()).severity)
        assertEquals(Severity.CLEAN, DomainAnalysis.spf(listOf("v=spf1 include:_spf.google.com -all")).severity)
        assertEquals(Severity.INFO, DomainAnalysis.spf(listOf("v=spf1 include:_spf.google.com ~all")).severity)
        assertEquals(Severity.EXPOSED, DomainAnalysis.spf(listOf("v=spf1 ?all")).severity)
        assertEquals(Severity.EXPOSED, DomainAnalysis.spf(listOf("v=spf1 +all")).severity)
        assertEquals(Severity.EXPOSED, DomainAnalysis.spf(listOf("v=spf1 ip4:1.2.3.4 all")).severity, "bare all is +all")
        assertEquals(Severity.EXPOSED, DomainAnalysis.spf(listOf("v=spf1 -all", "v=spf1 ~all")).severity, "duplicates are a permerror")
        assertEquals(Severity.INFO, DomainAnalysis.spf(listOf("google-site-verification=abc", "v=spf1 ip4:1.2.3.4")).severity)
    }

    @Test fun dmarcRules() {
        assertEquals(Severity.EXPOSED, DomainAnalysis.dmarc(emptyList()).severity)
        val none = DomainAnalysis.dmarc(listOf("v=DMARC1; p=none; rua=mailto:d@x.com"))
        assertEquals(Severity.EXPOSED, none.severity)
        assertTrue("monitoring only" in none.detail && "No rua" !in none.detail)
        assertTrue("No rua" in DomainAnalysis.dmarc(listOf("v=DMARC1; p=none")).detail)
        assertEquals(Severity.CLEAN, DomainAnalysis.dmarc(listOf("v=DMARC1; p=quarantine; pct=100")).severity)
        assertEquals(Severity.CLEAN, DomainAnalysis.dmarc(listOf("v=DMARC1;p=reject")).severity)
        assertEquals(Severity.EXPOSED, DomainAnalysis.dmarc(listOf("v=DMARC1; rua=mailto:a@b.c")).severity, "no p=")
    }

    @Test fun mxProviderAndNullMx() {
        val g = DomainAnalysis.mx(listOf(DnsRecord("x", 15, "5 alt1.aspmx.l.google.com."), DnsRecord("x", 15, "1 aspmx.l.google.com.")))
        assertTrue(g.detail.startsWith("aspmx.l.google.com (1)") && "Google Workspace" in g.detail, g.detail)
        assertEquals(Severity.CLEAN, DomainAnalysis.mx(listOf(DnsRecord("x", 15, "0 ."))).severity)
        assertTrue("does not receive mail" in DomainAnalysis.mx(emptyList()).detail)
    }

    @Test fun crtParsingDropsWildcardsApexAndLookalikes() {
        val names = DomainAnalysis.parseCrtNames(fixture("crtsh.json"), "northwind.io")
        assertEquals(listOf("api.northwind.io", "grafana.northwind.io", "staging.northwind.io", "www.northwind.io"), names)
        val f = DomainAnalysis.subdomains(names, "northwind.io")
        assertEquals(Severity.EXPOSED, f.severity)
        assertTrue("staging" in f.detail && "grafana" in f.detail)
        assertEquals(Severity.INFO, DomainAnalysis.subdomains(listOf("www.a.com", "shop.a.com"), "a.com").severity)
    }

    @Test fun revokedDkimKeysAreNotSigners() {
        assertTrue(!DomainAnalysis.isActiveDkim("v=DKIM1; p="), "example.com publishes a revoked wildcard key")
        assertTrue(!DomainAnalysis.isActiveDkim("v=DKIM1; k=rsa; p= "))
        assertTrue(DomainAnalysis.isActiveDkim("v=DKIM1; k=rsa; p=MIIBIjANBgkqh"))
        assertTrue(DomainAnalysis.isActiveDkim("k=rsa; p=MIIBIj"), "v= is optional")
        assertTrue(!DomainAnalysis.isActiveDkim("v=spf1 -all"))
    }

    @Test fun rdapParsesRegistrarAndDates() {
        val f = DomainAnalysis.rdap(fixture("rdap.json"), "northwind.io", now = Instant.parse("2026-10-14T00:00:00Z"))
        assertEquals(Severity.CLEAN, f.severity)
        assertTrue("Cloudflare, Inc." in f.detail && "created 2019-03-11" in f.detail && "expires 2027-03-11" in f.detail, f.detail)
        val soon = DomainAnalysis.rdap(fixture("rdap.json"), "northwind.io", now = Instant.parse("2027-02-20T00:00:00Z"))
        assertEquals(Severity.EXPOSED, soon.severity, "expiring within 30 days")
        val gone = DomainAnalysis.rdap(fixture("rdap.json"), "northwind.io", now = Instant.parse("2028-01-01T00:00:00Z"))
        assertTrue("EXPIRED" in gone.detail)
        assertTrue("redacted" in DomainAnalysis.rdap("{}", "x.io").detail)
    }
}

class DomainSourceTest {
    private fun dohAnswer(name: String, type: Int, vararg data: String) =
        """{"Status":0,"Answer":[${data.joinToString(",") { """{"name":"$name.","type":$type,"TTL":300,"data":${kotlinx.serialization.json.JsonPrimitive(it)}}""" }}]}"""

    private val empty = """{"Status":3}"""

    @Test fun fullScanAgainstMockInternet() = runTest {
        val (srv, router) = server(
            "/doh" to { req ->
                val name = req.requestUrl!!.queryParameter("name")!!
                val type = req.requestUrl!!.queryParameter("type")!!
                assertEquals("application/dns-json", req.getHeader("Accept"))
                json(
                    when {
                        name == "northwind.io" && type == "A" -> dohAnswer(name, 1, "104.21.44.9")
                        name == "northwind.io" && type == "MX" -> dohAnswer(name, 15, "1 aspmx.l.google.com.")
                        name == "northwind.io" && type == "NS" -> dohAnswer(name, 2, "gina.ns.cloudflare.com.")
                        name == "northwind.io" && type == "TXT" -> dohAnswer(name, 16, "\"v=spf1 include:_spf.google.com ~all\"", "\"google-site-verification=zzz\"")
                        name == "_dmarc.northwind.io" -> dohAnswer(name, 16, "\"v=DMARC1; p=none\"")
                        name == "google._domainkey.northwind.io" -> dohAnswer(name, 16, "\"v=DKIM1; k=rsa; p=MIIB\"")
                        else -> empty
                    },
                )
            },
            "/crt/" to { json(fixture("crtsh.json")) },
            "/rdap/domain/northwind.io" to { json(fixture("rdap.json")) },
        )
        srv.use {
            val f = DomainSource(Http(), srv.endpoints()).collect(ScanInput("northwind.io", InputType.DOMAIN))
            val byId = f.associateBy { it.id }
            assertEquals(10, f.size, f.map { it.id }.toString())
            assertTrue("104.21.44.9" in byId.getValue("DOMAIN-a").detail)
            assertEquals(Severity.INFO, byId.getValue("DOMAIN-spf").severity)
            assertEquals(Severity.EXPOSED, byId.getValue("DOMAIN-dmarc").severity)
            assertTrue("google" in byId.getValue("DOMAIN-dkim").detail)
            assertEquals(Severity.INFO, byId.getValue("DOMAIN-bimi").severity)
            assertEquals(Severity.EXPOSED, byId.getValue("DOMAIN-subs").severity)
            assertTrue("Cloudflare" in byId.getValue("DOMAIN-rdap").detail)
            assertTrue(router.seen.none { it.path!!.startsWith("/doh") && it.requestUrl!!.queryParameter("type") == "A" && it.requestUrl!!.queryParameter("name")!!.startsWith("_") })
        }
    }

    @Test fun oneFailingCheckDoesNotSinkTheRest() = runTest {
        val (srv, _) = server(
            "/doh" to { json(empty) },
            "/crt/" to { MockResponse().setResponseCode(502).setBody("Bad Gateway") },
            "/rdap/" to { MockResponse().setResponseCode(404) },
        )
        srv.use {
            val f = DomainSource(Http(), srv.endpoints()).collect(ScanInput("nowhere.example", InputType.DOMAIN))
            val subs = f.first { it.id == "DOMAIN-subs-error" }
            assertTrue("502" in subs.detail, subs.detail)
            assertTrue("No RDAP record" in f.first { it.id == "DOMAIN-rdap-error" }.detail)
            assertEquals(Severity.EXPOSED, f.first { it.id == "DOMAIN-spf" }.severity, "missing SPF still reported")
            assertEquals(Severity.EXPOSED, f.first { it.id == "DOMAIN-dmarc" }.severity)
        }
    }

    @Test fun dnsServfailIsAnErrorNotAnEmptyAnswer() = runTest {
        val (srv, _) = server("/doh" to { json("""{"Status":2}""") }, "/crt/" to { json("[]") }, "/rdap/" to { json(fixture("rdap.json")) })
        srv.use {
            val f = DomainSource(Http(), srv.endpoints()).collect(ScanInput("broken.example", InputType.DOMAIN))
            assertTrue("RCODE 2" in f.first { it.id == "DOMAIN-spf-error" }.detail)
            assertTrue(f.none { it.id == "DOMAIN-spf" }, "must not claim 'no SPF' when DNS failed")
        }
    }
}
