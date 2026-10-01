package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One check's verdict and, where there is one, why.
 *
 * [reason] is a plain-English diagnostic, such as "Not offered by this chip".
 * An app may show it, but it is not localised and its wording may change; decide
 * on [verdict]. It never contains personal data or credentials.
 */
@Parcelize
data class CheckResult(
    val verdict: CheckVerdict,
    val reason: String? = null,
) : Parcelable
