package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Where a certificate sits in the chain from a passport to its country. */
@Parcelize
enum class CertificateRole : Parcelable {
    /** The document signer, whose key signed the passport's EF.SOd. */
    DOCUMENT_SIGNER,

    /** A link certificate, joining an older country key to a newer one. */
    LINK,

    /** The Country Signing CA: the trust anchor a successful chain ends at. */
    CSCA,
}

/**
 * What an X.509 certificate in a passport's chain says, in plain types.
 *
 * Distinguished names are in RFC 2253 form. Times are milliseconds since the
 * epoch, UTC.
 */
@Parcelize
data class CertificateDetails(
    val role: CertificateRole,
    val subjectDn: String,
    val issuerDn: String,
    /**
     * ISO 3166-1 alpha-2 country from the subject's C attribute, such as "GB".
     *
     * Worth showing because the common name often identifies nothing: ICAO
     * 9303 suggests "Country Signing Authority" and many states use exactly
     * that. Note this is alpha-2, where an MRZ issuing state is alpha-3.
     */
    val subjectCountry: String? = null,
    /** The serial number, upper-case hex. */
    val serialNumberHex: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
    /** The key's algorithm, such as "EC" or "RSA". */
    val publicKeyAlgorithm: String,
    /** The RSA modulus or EC field size, when it can be read. */
    val keySizeBits: Int? = null,
    /** The EC curve's standard name, such as "brainpoolP256r1"; null for RSA or an unrecognised curve. */
    val ecCurve: String? = null,
    /** The algorithm the issuer signed this certificate with, such as "SHA256withECDSA". */
    val signatureAlgorithm: String,
    /** SHA-256 of the DER encoding, upper-case hex. */
    val sha256Fingerprint: String,
    /** SHA-1 of the DER encoding, upper-case hex; still the form many tools display. */
    val sha1Fingerprint: String,
) : Parcelable
