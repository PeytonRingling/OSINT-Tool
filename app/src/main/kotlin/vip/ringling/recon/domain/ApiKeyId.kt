package vip.ringling.recon.domain

enum class ApiKeyId(val label: String, val hint: String) {
    HIBP("Have I Been Pwned", "Required for email breach lookups (hibp-api-key)."),
    LEAKCHECK("LeakCheck", "Optional: raises limits. Without it the public endpoint is used."),
    VERIPHONE("Veriphone (phone lookup)", "Optional, free tier (1,000/month): current carrier and landline/mobile/VoIP type."),
    TWILIO_SID("Twilio Account SID (owner name)", "Optional, paid per lookup (~1¢): caller-ID name registered for a number, plus live carrier and VoIP check. Needs the Auth Token too."),
    TWILIO_TOKEN("Twilio Auth Token", "Pairs with the Account SID above. Stored encrypted; only ever sent to Twilio."),
    GITHUB("GitHub (app updates)", "Only needed while the repo is private: fine-grained token, this repo only, Contents: read."),
}
