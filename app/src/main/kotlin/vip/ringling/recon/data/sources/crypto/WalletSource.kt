package vip.ringling.recon.data.sources.crypto

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Bitcoin (Blockstream) and Ethereum (Blockscout) address activity from public explorers, plus scam-report links. Addresses are pseudonymous, not names. */
class WalletSource @Inject constructor(private val http: Http, private val endpoints: Endpoints) : OsintSource {
    override val id = ModuleId.CRYPTO
    override val supportedInputTypes = setOf(InputType.WALLET)

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val a = input.raw
        suspend fun get(url: String, accept: String? = null) = http.execute(Request.Builder().url(url).apply { accept?.let { header("Accept", it) } }.build()) { r ->
            when (r.code) { 200 -> r.body.string(); 404 -> ""; else -> throw HttpException(r.code, "Explorer returned HTTP ${r.code}") }
        }
        if (a.startsWith("0x")) {
            val acct = get("${endpoints.blockscout}/api/v2/addresses/$a", "application/json")
            val counters = runCatching { get("${endpoints.blockscout}/api/v2/addresses/$a/counters", "application/json") }.getOrDefault("")
            send(WalletAnalysis.eth(a, acct, counters))
        } else {
            val info = get("${endpoints.blockstream}/api/address/$a")
            val txs = runCatching { get("${endpoints.blockstream}/api/address/$a/txs") }.getOrDefault("")
            send(WalletAnalysis.btc(a, info, txs))
        }
    }
}

object WalletAnalysis {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.l(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    private fun btc(sats: Long) = BigDecimal(sats).movePointLeft(8).stripTrailingZeros().toPlainString()

    fun btc(address: String, info: String, txs: String): Finding {
        if (info.isBlank()) return Finding("CRYPTO-btc", ModuleId.CRYPTO, "Address not found", "The explorer has no record of this Bitcoin address.", Severity.INFO)
        val o = json.parseToJsonElement(info).jsonObject
        val chain = o["chain_stats"]?.jsonObject ?: JsonObject(emptyMap())
        val mem = o["mempool_stats"]?.jsonObject ?: JsonObject(emptyMap())
        val received = chain.l("funded_txo_sum"); val spent = chain.l("spent_txo_sum")
        val txCount = chain.l("tx_count") + mem.l("tx_count")
        val latest = runCatching { json.parseToJsonElement(txs).jsonArray.mapNotNull { it.jsonObject["status"]?.jsonObject?.get("block_time")?.jsonPrimitive?.contentOrNull?.toLongOrNull() }.maxOrNull() }.getOrNull()
        val service = txCount >= 5_000
        return Finding(
            "CRYPTO-btc", ModuleId.CRYPTO, "Bitcoin · ${btc(received - spent)} BTC",
            if (txCount == 0L) "No transactions: unused address." else "$txCount transactions" + (latest?.let { " · last confirmed ${Instant.ofEpochSecond(it).toString().take(10)}" } ?: "") +
                if (service) ". Very high volume: likely an exchange, service or well-known address." else ".",
            Severity.INFO, url = "https://blockstream.info/address/$address", confidence = Confidence.HIGH,
            tags = listOfNotNull("BTC", if (service) "High volume" else null),
            facts = listOf(Fact("Balance", "${btc(received - spent)} BTC"), Fact("Total received", "${btc(received)} BTC"), Fact("Total sent", "${btc(spent)} BTC"), Fact("Transactions", txCount.toString())) +
                listOfNotNull(latest?.let { Fact("Latest confirmed activity", Instant.ofEpochSecond(it).toString().take(10)) }),
            links = listOf(
                Link("Blockstream explorer", "https://blockstream.info/address/$address"), Link("WalletExplorer (clustering)", "https://www.walletexplorer.com/address/$address"),
                Link("Bitcoin Abuse reports", "https://www.bitcoinabuse.com/reports/$address"), Link("Chainabuse reports", "https://www.chainabuse.com/address/$address"),
                Link("OXT / Mempool", "https://mempool.space/address/$address"), Link("Search the address", "https://www.google.com/search?q=%22$address%22"),
            ),
        )
    }

    fun eth(address: String, acct: String, counters: String): Finding {
        if (acct.isBlank()) return Finding("CRYPTO-eth", ModuleId.CRYPTO, "Address not found", "Blockscout has no record of this Ethereum address.", Severity.INFO)
        val o = json.parseToJsonElement(acct).jsonObject
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        val eth = s("coin_balance")?.toBigIntegerOrNull()?.let { BigDecimal(it).movePointLeft(18).setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString() } ?: "?"
        val c = runCatching { json.parseToJsonElement(counters).jsonObject }.getOrNull()
        val txs = c?.get("transactions_count")?.jsonPrimitive?.contentOrNull; val transfers = c?.get("token_transfers_count")?.jsonPrimitive?.contentOrNull
        val contract = o["is_contract"]?.jsonPrimitive?.contentOrNull == "true"
        val ens = s("ens_domain_name")
        return Finding(
            "CRYPTO-eth", ModuleId.CRYPTO, "Ethereum · $eth ETH",
            (if (contract) "Smart contract. " else "Externally owned account. ") + (ens?.let { "ENS name: $it. " } ?: "") + (txs?.let { "$it transactions." } ?: ""),
            Severity.INFO, url = "https://etherscan.io/address/$address", confidence = Confidence.HIGH, tags = listOfNotNull("ETH", if (contract) "Contract" else null),
            facts = listOfNotNull(
                Fact("Balance", "$eth ETH"), ens?.let { Fact("ENS name", it) }, Fact("Type", if (contract) "Smart contract" else "Wallet"),
                txs?.let { Fact("Transactions", it) }, transfers?.let { Fact("Token transfers", it) },
                s("creator_address_hash")?.let { Fact("Created by", it) }, o["has_tokens"]?.jsonPrimitive?.contentOrNull?.let { Fact("Holds tokens", if (it == "true") "Yes" else "No") },
            ),
            pivots = listOfNotNull(s("creator_address_hash")?.let { vip.ringling.recon.domain.Pivot("Wallet intel (creator)", it) }),
            links = listOf(
                Link("Etherscan", "https://etherscan.io/address/$address"), Link("Blockscout", "https://eth.blockscout.com/address/$address"),
                Link("Chainabuse reports", "https://www.chainabuse.com/address/$address"), Link("Search the address", "https://www.google.com/search?q=%22$address%22"),
            ),
        )
    }
}
