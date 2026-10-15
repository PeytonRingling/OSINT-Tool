package vip.ringling.recon.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.data.sources.code.GithubAnalysis
import vip.ringling.recon.data.sources.code.GithubSource
import vip.ringling.recon.data.sources.crypto.WalletAnalysis
import vip.ringling.recon.data.sources.crypto.WalletSource
import vip.ringling.recon.data.sources.domain.DomainExtras
import vip.ringling.recon.data.sources.dorks.Dorks
import vip.ringling.recon.data.sources.emailintel.EmailAnalysis
import vip.ringling.recon.data.sources.network.IpAnalysis
import vip.ringling.recon.data.sources.network.IpSource
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.apply

class InputDetectionTest {
    @Test fun ipsAndWalletsAreDetectedBeforePhonesAndUsernames() {
        assertEquals(InputType.IP, InputDetector.detect("192.168.100.100"))
        assertEquals(InputType.IP, InputDetector.detect("8.8.8.8"))
        assertEquals(InputType.IP, InputDetector.detect("2606:4700:4700::1111"))
        assertEquals(InputType.WALLET, InputDetector.detect("0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe"))
        assertEquals(InputType.WALLET, InputDetector.detect("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"))
        assertEquals(InputType.WALLET, InputDetector.detect("bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"))
        assertEquals(InputType.PHONE, InputDetector.detect("(208) 555-0142"))
        assertEquals(InputType.DOMAIN, InputDetector.detect("example.com"))
        assertEquals(InputType.USERNAME, InputDetector.detect("jane_doe"))
        assertEquals(InputType.USERNAME, InputDetector.detect("999.999.999.999".replace("999.999.999.999", "a1b2c3")))
    }
}

class IpAnalysisTest {
    @Test fun reservedAndReverseNames() {
        assertTrue(IpAnalysis.reservedReason("10.0.0.1")!!.contains("Private"))
        assertTrue(IpAnalysis.reservedReason("127.0.0.1")!!.contains("Loopback"))
        assertTrue(IpAnalysis.reservedReason("100.64.1.1")!!.contains("Carrier-grade"))
        assertNull(IpAnalysis.reservedReason("8.8.8.8"))
        assertEquals("8.8.8.8.in-addr.arpa", IpAnalysis.reverseName("8.8.8.8"))
        assertTrue(IpAnalysis.reverseName("2606:4700:4700::1111").endsWith(".ip6.arpa") && IpAnalysis.reverseName("2606:4700:4700::1111").startsWith("1.1.1.1."))
    }

    @Test fun rdapFixtureYieldsOrganisationAndAbuseContact() {
        val f = assertNotNull(IpAnalysis.rdap("8.8.8.8", fixture("rdap_ip.json")))
        assertTrue(f.title.contains("Google"), f.title)
        assertTrue(f.facts.any { it.label == "Organisation" }); assertTrue(f.facts.any { it.label == "CIDR" && "8.8.8.0/24" in it.value })
    }

    @Test fun ipinfoTorAndPtr() {
        val g = assertNotNull(IpAnalysis.ipinfo("8.8.8.8", """{"ip":"8.8.8.8","hostname":"dns.google","city":"Mountain View","region":"California","country":"US","loc":"38.0088,-122.1175","org":"AS15169 Google LLC","anycast":true}"""))
        assertEquals(38.0088, g.geo!!.lat, 1e-6); assertTrue(g.pivots.single().query == "dns.google"); assertEquals(vip.ringling.recon.domain.Confidence.LOW, g.confidence)
        assertEquals(Severity.EXPOSED, IpAnalysis.tor("1.2.3.4", "9.9.9.9\n1.2.3.4\n")!!.severity)
        assertEquals(Severity.CLEAN, IpAnalysis.tor("1.2.3.4", "9.9.9.9\n")!!.severity)
        assertEquals("example.co.uk", IpAnalysis.ptr("1.1.1.1", listOf("srv1.example.co.uk")).pivots.single().query)
        assertEquals("No reverse DNS", IpAnalysis.ptr("1.1.1.1", emptyList()).title)
    }

    @Test fun sourceRunsAllChecksAndSurvivesFailures() = runTest {
        val (srv, _) = server(
            "/rdap/ip/" to { MockResponse().setResponseCode(200).setBody(fixture("rdap_ip.json")) },
            "/ipinfo/" to { json("""{"ip":"8.8.8.8","city":"X","country":"US","loc":"1.0,2.0","org":"AS1 Org"}""") },
            "/doh" to { json("""{"Status":0,"Answer":[{"name":"8.8.8.8.in-addr.arpa","type":12,"data":"dns.google."}]}""") },
            "/idb/" to { MockResponse().setResponseCode(404) },
            "/tor" to { MockResponse().setResponseCode(503) },
        )
        srv.use {
            val f = IpSource(Http(), srv.endpoints()).collect(ScanInput("8.8.8.8", InputType.IP))
            val ids = f.map { it.id }.toSet()
            assertTrue(setOf("NETWORK-rdap", "NETWORK-geo", "NETWORK-ptr", "NETWORK-tor-error", "NETWORK-links").all { it in ids }, ids.toString())
            val priv = IpSource(Http(), srv.endpoints()).collect(ScanInput("10.1.2.3", InputType.IP)).single()
            assertEquals("NETWORK-reserved", priv.id)
        }
    }
}

class WalletAnalysisTest {
    @Test fun bitcoinBalanceAndActivity() {
        val info = """{"address":"1A","chain_stats":{"funded_txo_count":2,"funded_txo_sum":150000000,"spent_txo_count":1,"spent_txo_sum":50000000,"tx_count":3},"mempool_stats":{"tx_count":0,"funded_txo_sum":0,"spent_txo_sum":0}}"""
        val txs = """[{"txid":"a","status":{"confirmed":true,"block_time":1700000000}},{"txid":"b","status":{"confirmed":true,"block_time":1600000000}}]"""
        val f = WalletAnalysis.btc("1A", info, txs)
        assertEquals("Bitcoin · 1 BTC", f.title); assertTrue("2023-11-14" in f.detail, f.detail)
        assertEquals("1.5 BTC", f.facts.first { it.label == "Total received" }.value)
        assertTrue(WalletAnalysis.btc("1A", "", "").title == "Address not found")
        assertTrue("High volume" in WalletAnalysis.btc("1A", """{"chain_stats":{"tx_count":9000,"funded_txo_sum":1,"spent_txo_sum":0},"mempool_stats":{}}""", "").tags)
    }

    @Test fun ethereumAccountAndContract() {
        val f = WalletAnalysis.eth("0xabc", """{"coin_balance":"5774491794456062094343","ens_domain_name":"vitalik.eth","is_contract":false,"has_tokens":true,"creator_address_hash":null}""", """{"transactions_count":"12","token_transfers_count":"3"}""")
        assertTrue(f.title.startsWith("Ethereum · 5774.4917"), f.title)
        assertTrue(f.facts.any { it.label == "ENS name" && it.value == "vitalik.eth" } && f.facts.any { it.label == "Transactions" && it.value == "12" })
        assertTrue("Contract" in WalletAnalysis.eth("0x", """{"coin_balance":"0","is_contract":true}""", "").tags)
    }

    @Test fun sourceRoutesByChain() = runTest {
        val (srv, router) = server(
            "/bs/api/address/1A1" to { json("""{"chain_stats":{"tx_count":1,"funded_txo_sum":100,"spent_txo_sum":0},"mempool_stats":{}}""") },
            "/scout/api/v2/addresses/" to { json("""{"coin_balance":"1000000000000000000","is_contract":false}""") },
        )
        srv.use {
            val btc = WalletSource(Http(), srv.endpoints()).collect(ScanInput("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa".let { "1A1" }, InputType.WALLET)).single()
            assertEquals("CRYPTO-btc", btc.id)
            val eth = WalletSource(Http(), srv.endpoints()).collect(ScanInput("0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe", InputType.WALLET)).single()
            assertEquals("CRYPTO-eth", eth.id); assertTrue(router.seen.any { it.getHeader("Accept") == "application/json" })
        }
    }
}

class GithubAnalysisTest {
    private val profile = """{"login":"octo","id":583231,"name":"The Octocat","company":"@github","blog":"https://octo.example/blog","location":"SF","email":"octo@example.com","bio":"hi","twitter_username":"octo_x","public_repos":8,"followers":10,"following":1,"created_at":"2011-01-25T18:44:36Z","html_url":"https://github.com/octo","avatar_url":"https://a/1.png"}"""
    private val events = """[{"type":"WatchEvent"},{"type":"PushEvent","payload":{"commits":[
        {"author":{"name":"Real Name","email":"Real@Example.org"}},{"author":{"name":"Real Name","email":"real@example.org"}},
        {"author":{"name":"Bot","email":"12345+octo@users.noreply.github.com"}},{"author":{"name":"Pal","email":"pal@work.io"}}]}}]"""

    @Test fun profileFactsAndPivots() {
        val f = GithubAnalysis.profile(profile, listOf("github"), 2)
        assertEquals("GitHub · @octo", f.title); assertEquals("https://a/1.png", f.imageUrl)
        assertTrue(f.facts.any { it.label == "Organisations" && it.value == "github" } && f.facts.any { it.label == "Public SSH keys" && it.value == "2" })
        assertEquals(setOf("octo@example.com", "octo.example", "octo_x"), f.pivots.map { it.query }.toSet())
    }

    @Test fun commitEmailsAreDedupedAndNoreplyDropped() {
        val f = assertNotNull(GithubAnalysis.commitEmails("octo", events))
        assertEquals(setOf("real@example.org", "pal@work.io"), f.pivots.map { it.query }.toSet())
        assertEquals(Severity.EXPOSED, f.severity); assertTrue("noreply" !in f.detail)
        assertNull(GithubAnalysis.commitEmails("octo", """[{"type":"PushEvent","payload":{"commits":[{"author":{"email":"1+x@users.noreply.github.com"}}]}}]"""))
        assertNull(GithubAnalysis.commitEmails("octo", "<<garbage"))
    }

    @Test fun sourceSendsTokenHandlesMissingAndRateLimit() = runTest {
        val (srv, router) = server(
            "/gh/users/octo/events" to { json(events) }, "/gh/users/octo/orgs" to { json("""[{"login":"github"}]""") }, "/gh/users/octo/keys" to { json("[]") },
            "/gh/users/octo" to { json(profile) },
            "/gh/users/ghost" to { MockResponse().setResponseCode(404) },
            "/gh/users/limited" to { MockResponse().setResponseCode(403) },
        )
        srv.use {
            val src = GithubSource(Http(), srv.endpoints(), InMemoryApiKeys(mapOf(ApiKeyId.GITHUB to "tok123")))
            val f = src.collect(ScanInput("octo", InputType.USERNAME))
            assertEquals(setOf("CODE-github", "CODE-github-emails"), f.map { it.id }.toSet())
            assertTrue(router.seen.all { it.getHeader("Authorization") == "Bearer tok123" })
            assertTrue(src.collect(ScanInput("ghost", InputType.USERNAME)).isEmpty())
            assertEquals("GitHub unavailable", src.collect(ScanInput("limited", InputType.USERNAME)).single().title)
            assertTrue(src.collect(ScanInput("bad_name!", InputType.USERNAME)).isEmpty(), "invalid GitHub login is not requested")
        }
    }
}

class EmailAndDorksTest {
    @Test fun gmailTricksRoleAndDisposable() {
        val g = EmailAnalysis.analyze("john.smith+shop@gmail.com", false, listOf("alt1.gmail-smtp-in.l.google.com"))
        assertEquals("johnsmith@gmail.com", g.facts.first { it.label == "Canonical Gmail address" }.value)
        assertTrue(g.pivots.any { it.query == "john.smith" } && g.pivots.any { it.query == "johnsmith@gmail.com" } && g.pivots.any { it.query == "john.smith@gmail.com" })
        assertTrue("shop" in g.detail && "Google" in g.detail)
        val d = EmailAnalysis.analyze("x@mailinator.com", true, null)
        assertEquals(Severity.EXPOSED, d.severity); assertEquals("Disposable address", d.title)
        val r = EmailAnalysis.analyze("info@acme.io", false, emptyList())
        assertTrue("Role account" in r.tags && "No MX records" in r.tags && r.pivots.none { it.query == "info" } && r.pivots.any { it.query == "acme.io" })
        assertEquals("Microsoft 365 / Outlook", EmailAnalysis.provider(listOf("acme-io.mail.protection.outlook.com")))
    }

    @Test fun dorksCoverEveryTypeAndEncodeQueries() {
        listOf(ScanInput("a@b.com", InputType.EMAIL), ScanInput("jane", InputType.USERNAME), ScanInput("+12085550142", InputType.PHONE), ScanInput("b.com", InputType.DOMAIN),
            ScanInput("8.8.8.8", InputType.IP), ScanInput("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", InputType.WALLET)).forEach { i ->
            val f = Dorks.finding(i)
            assertTrue(f.links.size >= 6, "${i.type}: ${f.links.size}")
            assertTrue(f.links.all { it.url.startsWith("https://") && ' ' !in it.url && '"' !in it.url })
        }
        assertTrue(Dorks.queries(ScanInput("+12085550142", InputType.PHONE)).any { it.contains("(208) 555-0142") })
    }

    @Test fun urlscanSummarisesHostingHistory() {
        val f = assertNotNull(DomainExtras.urlscan("example.com", fixture("urlscan.json")))
        assertTrue(f.title.contains("public scan") && f.facts.any { it.label == "IPs seen" } && f.pivots.isNotEmpty())
        assertNull(DomainExtras.urlscan("x.com", """{"total":0,"results":[]}"""))
    }
}

class MapRobustnessTest {
    private fun reg(id: String, lat: Double, lng: Double) = vip.ringling.recon.domain.Finding(id, vip.ringling.recon.domain.ModuleId.REGISTRY, id, "d", Severity.INFO, geo = vip.ringling.recon.domain.GeoPoint(lat, lng))

    @Test fun farAwayPinsAreDroppedButRecordsKept() {
        var s = vip.ringling.recon.domain.ScanSession(ScanInput("83646", InputType.ZIP))
        listOf(reg("a", 43.60, -116.40), reg("b", 43.61, -116.41), reg("c", 43.62, -116.39), reg("far", 47.68, -116.78), reg("d", 43.59, -116.42)).forEach {
            s = s.apply(vip.ringling.recon.domain.ScanEvent.Found(it))
        }
        val far = s.findings.first { it.id == "far" }
        assertEquals(null, far.geo); assertTrue("Location unverified" in far.tags)
        assertEquals(4, s.findings.count { it.geo != null })
        assertEquals(5, s.findings.size, "the record itself stays")
    }

    @Test fun fewPointsAreNeverJudged() {
        assertTrue(vip.ringling.recon.domain.Geo.outliers(listOf(vip.ringling.recon.domain.GeoPoint(43.0, -116.0), vip.ringling.recon.domain.GeoPoint(47.0, -116.0))).isEmpty())
    }

    @Test fun markerPositionBeatsMapCentre() {
        val html = "center: new google.maps.LatLng(43.0000000, -116.0000000) }; var m = new google.maps.Marker({ position: new google.maps.LatLng(43.6150000, -116.2020000) });"
        val g = vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter.parseGeo(html)!!
        assertEquals(43.615, g.lat, 1e-6)
        assertEquals(43.0, vip.ringling.recon.data.sources.registry.IdahoRegistryAdapter.parseGeo("new google.maps.LatLng(43.0000000, -116.0000000)")!!.lat, 1e-6)
    }
}
