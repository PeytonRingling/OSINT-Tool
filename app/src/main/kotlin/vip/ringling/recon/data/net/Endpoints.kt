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
    val idahoSor: String = "https://apps.isp.idaho.gov/sor_id",
)
