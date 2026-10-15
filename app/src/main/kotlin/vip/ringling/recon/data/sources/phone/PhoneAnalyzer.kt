package vip.ringling.recon.data.sources.phone

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.Phonenumber
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import java.util.Locale

data class PhoneInfo(
    val e164: String,
    val international: String,
    val national: String,
    val countryCallingCode: Int,
    val regionCode: String,
    val countryName: String,
    val valid: Boolean,
    val possibleReason: String,
    val lineType: String,
    val place: String,
    val carrier: String,
    val timeZones: List<String>,
    val nanp: Boolean,
)

/** Everything derivable from the digits alone, on-device, via Google's libphonenumber data. No network. */
object PhoneAnalyzer {
    private val util get() = PhoneNumberUtil.getInstance()

    sealed interface Result {
        data class Ok(val info: PhoneInfo) : Result
        data class Unparseable(val reason: String) : Result
    }

    fun analyze(raw: String, defaultRegion: String): Result {
        val number: Phonenumber.PhoneNumber = try {
            util.parse(raw, defaultRegion.uppercase().ifBlank { "US" })
        } catch (e: NumberParseException) {
            return Result.Unparseable(
                when (e.errorType) {
                    NumberParseException.ErrorType.INVALID_COUNTRY_CODE -> "The country code isn't recognized. Start with + and the country code."
                    NumberParseException.ErrorType.TOO_SHORT_AFTER_IDD, NumberParseException.ErrorType.NOT_A_NUMBER -> "That doesn't look like a phone number."
                    NumberParseException.ErrorType.TOO_SHORT_NSN -> "Too few digits for a phone number."
                    NumberParseException.ErrorType.TOO_LONG -> "Too many digits for a phone number."
                },
            )
        }
        val region = util.getRegionCodeForNumber(number) ?: defaultRegion.uppercase()
        val type = util.getNumberType(number)
        val valid = util.isValidNumber(number)
        return Result.Ok(
            PhoneInfo(
                e164 = util.format(number, PhoneNumberFormat.E164),
                international = util.format(number, PhoneNumberFormat.INTERNATIONAL),
                national = util.format(number, PhoneNumberFormat.NATIONAL),
                countryCallingCode = number.countryCode,
                regionCode = region,
                countryName = Locale("", region).getDisplayCountry(Locale.ENGLISH).ifBlank { region },
                valid = valid,
                possibleReason = describe(util.isPossibleNumberWithReason(number)),
                lineType = typeLabel(type),
                place = if (valid) PhoneNumberOfflineGeocoder.getInstance().getDescriptionForNumber(number, Locale.ENGLISH).orEmpty() else "",
                carrier = if (valid) PhoneNumberToCarrierMapper.getInstance().getNameForNumber(number, Locale.ENGLISH).orEmpty() else "",
                timeZones = if (valid) PhoneNumberToTimeZonesMapper.getInstance().getTimeZonesForNumber(number).filter { it != PhoneNumberToTimeZonesMapper.getUnknownTimeZone() } else emptyList(),
                nanp = number.countryCode == 1,
            ),
        )
    }

    private fun describe(r: PhoneNumberUtil.ValidationResult) = when (r) {
        PhoneNumberUtil.ValidationResult.IS_POSSIBLE, PhoneNumberUtil.ValidationResult.IS_POSSIBLE_LOCAL_ONLY -> "Plausible length"
        PhoneNumberUtil.ValidationResult.INVALID_COUNTRY_CODE -> "Unknown country code"
        PhoneNumberUtil.ValidationResult.TOO_SHORT -> "Too short"
        PhoneNumberUtil.ValidationResult.TOO_LONG -> "Too long"
        PhoneNumberUtil.ValidationResult.INVALID_LENGTH -> "Wrong length for this country"
    }

    private fun typeLabel(t: PhoneNumberType) = when (t) {
        PhoneNumberType.FIXED_LINE -> "Landline"
        PhoneNumberType.MOBILE -> "Mobile"
        PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Landline or mobile (the numbering plan can't tell)"
        PhoneNumberType.TOLL_FREE -> "Toll-free"
        PhoneNumberType.PREMIUM_RATE -> "Premium-rate"
        PhoneNumberType.SHARED_COST -> "Shared-cost"
        PhoneNumberType.VOIP -> "VoIP"
        PhoneNumberType.PERSONAL_NUMBER -> "Personal number"
        PhoneNumberType.PAGER -> "Pager"
        PhoneNumberType.UAN -> "Company-wide (UAN)"
        PhoneNumberType.VOICEMAIL -> "Voicemail"
        PhoneNumberType.UNKNOWN -> "Unknown"
    }
}
