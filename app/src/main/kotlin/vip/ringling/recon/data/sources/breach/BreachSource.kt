package vip.ringling.recon.data.sources.breach

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** HIBP (email) and LeakCheck (email/phone/username) run concurrently; a failure in one never hides the other. */
class BreachSource @Inject constructor(
    http: Http,
    endpoints: Endpoints,
    private val keys: ApiKeys,
) : OsintSource {
    override val id = ModuleId.BREACH
    override val supportedInputTypes = setOf(InputType.EMAIL, InputType.PHONE, InputType.USERNAME)

    private val hibp = HibpClient(http, endpoints.hibp)
    private val leak = LeakCheckClient(http, endpoints.leakCheckApi, endpoints.leakCheckPublic)

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val hibpKey = keys.get(ApiKeyId.HIBP)
        val leakKey = keys.get(ApiKeyId.LEAKCHECK)
        var failures = 0
        var attempted = 0
        val errors = mutableListOf<String>()

        if (input.type == InputType.EMAIL) {
            if (hibpKey == null) {
                send(note("hibp-nokey", "HIBP skipped", "Add a Have I Been Pwned API key in Settings to check breaches and pastes."))
            } else {
                launch {
                    attempted++
                    runCatching {
                        val breaches = hibp.breaches(input.raw, hibpKey)
                        if (breaches.isEmpty()) send(HibpMapper.noBreaches())
                        breaches.sortedByDescending { it.breachDate }.forEach { send(HibpMapper.breachFinding(it)) }
                        send(HibpMapper.pasteFinding(hibp.pastes(input.raw, hibpKey)))
                    }.onFailure { e -> failures++; errors += e.message.orEmpty(); send(failure("hibp", "HIBP", e)) }
                }
            }
        }

        val leakUsable = leakKey != null || input.type == InputType.EMAIL
        if (leakUsable) {
            launch {
                attempted++
                runCatching { send(LeakCheckMapper.finding(leak.lookup(input.raw, input.type, leakKey))) }
                    .onFailure { e -> failures++; errors += e.message.orEmpty(); send(failure("leakcheck", "LeakCheck", e)) }
            }
        } else {
            send(note("leakcheck-nokey", "LeakCheck skipped", "A LeakCheck API key is required for ${input.type.label.lowercase()} lookups. Add one in Settings."))
        }
    }

    private fun note(key: String, title: String, detail: String) =
        Finding("BREACH-$key", ModuleId.BREACH, title, detail, Severity.INFO)

    private fun failure(key: String, name: String, e: Throwable) =
        Finding("BREACH-$key-error", ModuleId.BREACH, "$name unavailable", e.message ?: e.javaClass.simpleName, Severity.INFO)
}
