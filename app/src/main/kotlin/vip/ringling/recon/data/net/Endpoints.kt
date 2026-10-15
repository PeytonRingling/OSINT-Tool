package vip.ringling.recon.data.net

/** Base URLs, overridable so tests can point every source at a MockWebServer. */
data class Endpoints(
    val hibp: String = "https://haveibeenpwned.com/api/v3",
    val leakCheckApi: String = "https://leakcheck.io/api/v2",
    val leakCheckPublic: String = "https://leakcheck.io/api/public",
    val doh: String = "https://cloudflare-dns.com/dns-query",
    val crtSh: String = "https://crt.sh",
    val rdap: String = "https://rdap.org",
    val spotify: String = "https://spclient.wg.spotify.com",
    val gravatar: String = "https://gravatar.com",
    val github: String = "https://api.github.com",
    val notes800: String = "https://800notes.com",
    val veriphone: String = "https://api.veriphone.io/v2",
    val twilio: String = "https://lookups.twilio.com/v2",
    val archive: String = "https://archive.org",
    val internetDb: String = "https://internetdb.shodan.io",
    val ipinfo: String = "https://ipinfo.io",
    val torList: String = "https://check.torproject.org/torbulkexitlist",
    val urlscan: String = "https://urlscan.io",
    val blockstream: String = "https://blockstream.info",
    val blockscout: String = "https://eth.blockscout.com",
    val wordpress: String = "https://public-api.wordpress.com",
    val duolingo: String = "https://www.duolingo.com",
    val updateRepo: String = "testuser/OSINT-Tool",
    val idahoSor: String = "https://apps.isp.idaho.gov/sor_id",
)
