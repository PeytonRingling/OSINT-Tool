package vip.ringling.recon.domain

enum class ApiKeyId(val label: String, val hint: String) {
    HIBP("Have I Been Pwned", "Required for email breach lookups (hibp-api-key)."),
    LEAKCHECK("LeakCheck", "Optional for email; required for phone and username lookups."),
}
