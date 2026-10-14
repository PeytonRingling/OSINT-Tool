package vip.ringling.recon.data.mock

import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput

/** Streams canned findings with staggered latency so the UI's streaming behaviour is visible. */
abstract class MockSource(
    final override val id: ModuleId,
    final override val supportedInputTypes: Set<InputType>,
    private val startDelay: Long,
    private val perItemDelay: Long,
) : OsintSource {
    override suspend fun run(input: ScanInput): Flow<Finding> = flow {
        delay(startDelay)
        for (f in MockData.forModule(id, input)) {
            emit(f)
            delay(perItemDelay)
        }
    }
}

class MockBreachSource @Inject constructor() : MockSource(
    ModuleId.BREACH, setOf(InputType.EMAIL, InputType.PHONE, InputType.USERNAME), 900, 380,
)

class MockIdentitySource @Inject constructor() : MockSource(
    ModuleId.IDENTITY, setOf(InputType.EMAIL, InputType.PHONE, InputType.USERNAME), 1500, 450,
)

class MockDomainSource @Inject constructor() : MockSource(
    ModuleId.DOMAIN, setOf(InputType.DOMAIN), 700, 300,
)

class MockRegistrySource @Inject constructor() : MockSource(
    ModuleId.REGISTRY, setOf(InputType.ZIP), 1800, 500,
)
