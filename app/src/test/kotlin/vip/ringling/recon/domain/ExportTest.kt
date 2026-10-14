package vip.ringling.recon.domain

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExportTest {
    private val f = Finding("x", ModuleId.BREACH, "Adobe", "d", Severity.EXPOSED, "https://e.com", "{\"a\":1}")

    @Test fun findingJsonRoundTrips() {
        val obj = Json.parseToJsonElement(Export.finding(f)).jsonObject
        assertTrue(obj["module"]!!.jsonPrimitive.content == "BREACH")
        assertTrue(Json.decodeFromString<Finding>(Export.finding(f)) == f)
    }

    @Test fun scanJsonIncludesRisk() {
        val obj = Json.parseToJsonElement(Export.scan(ScanInput("a@b.com", InputType.EMAIL), listOf(f))).jsonObject
        assertTrue(obj["risk"]!!.jsonPrimitive.content == "14")
    }
}
