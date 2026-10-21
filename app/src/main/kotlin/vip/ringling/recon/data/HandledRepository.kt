package vip.ringling.recon.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.ringling.recon.domain.Finding

private val Context.handledStore by preferencesDataStore("recon_handled")

/** "Mark as handled" flags for findings (stored by a content hash, never the finding itself). On-device only. */
@Singleton
class HandledRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringSetPreferencesKey("handled")
    val handled: Flow<Set<String>> = context.handledStore.data.map { it[key] ?: emptySet() }

    private val stepsKey = stringSetPreferencesKey("steps")
    /** Ticked "What to do" steps, as "<finding key>:<step index>". */
    val doneSteps: Flow<Set<String>> = context.handledStore.data.map { it[stepsKey] ?: emptySet() }

    /** Ticks or unticks one step of a finding's checklist; finishing the last step marks the finding handled, unticking one un-marks it. */
    suspend fun setStep(f: Finding, index: Int, done: Boolean, total: Int) {
        val fk = keyOf(f)
        context.handledStore.edit { p ->
            val steps = (p[stepsKey] ?: emptySet()).let { if (done) it + "$fk:$index" else it - "$fk:$index" }
            p[stepsKey] = steps
            val all = (0 until total).all { "$fk:$it" in steps }
            p[key] = (p[key] ?: emptySet()).let { if (all && total > 0) it + fk else it - fk }
        }
    }

    suspend fun clear() { context.handledStore.edit { it.clear() } }

    suspend fun set(f: Finding, on: Boolean) {
        val id = keyOf(f)
        context.handledStore.edit { p -> p[key] = (p[key] ?: emptySet()).let { if (on) it + id else it - id } }
    }

    companion object {
        /** Stable key from the finding's module, id, title and detail. */
        fun keyOf(f: Finding): String =
            java.security.MessageDigest.getInstance("SHA-256").digest("${f.module}|${f.id}|${f.title}|${f.detail}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
