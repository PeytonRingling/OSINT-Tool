package vip.ringling.recon.ui.lab

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import vip.ringling.recon.data.lab.LinkTracer
import vip.ringling.recon.data.lab.TraceResult
import vip.ringling.recon.data.sources.breach.PwnedPasswords

/** Lab tools are one-shot and keep nothing: pasted headers, links and passwords live only in the screen's state. */
@HiltViewModel
class LabViewModel @Inject constructor(private val tracer: LinkTracer, private val pwned: PwnedPasswords, private val intel: vip.ringling.recon.data.lab.LinkIntel) : ViewModel() {
    suspend fun domainCheck(host: String) = intel.check(host)
    suspend fun trace(url: String): TraceResult = tracer.trace(url)
    suspend fun passwordCount(pw: String): Long = pwned.count(pw)
}
