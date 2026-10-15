package vip.ringling.recon.domain

enum class ApiKeyId(val label: String, val hint: String) {
    HIBP("Have I Been Pwned", "Required for email breach lookups (hibp-api-key)."),
    LEAKCHECK("LeakCheck", "Optional: raises limits. Without it the public endpoint is used."),
    GITHUB("GitHub (app updates)", "Only needed while the repo is private: fine-grained token, this repo only, Contents: read."),
}
