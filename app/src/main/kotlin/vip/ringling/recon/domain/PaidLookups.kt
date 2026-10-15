package vip.ringling.recon.domain

/** Sources that offer lookups costing real money. They are never run by a scan; only after the user confirms a [PaidAction]. */
interface PaidLookups {
    suspend fun runPaid(actionId: String, input: ScanInput): List<Finding>
}
