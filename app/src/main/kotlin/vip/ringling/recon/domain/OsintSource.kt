package vip.ringling.recon.domain

import kotlinx.coroutines.flow.Flow

/** Every OSINT module implements this; the UI only ever sees [Finding]s. */
interface OsintSource {
    val id: ModuleId
    val supportedInputTypes: Set<InputType>
    suspend fun run(input: ScanInput): Flow<Finding>
}
