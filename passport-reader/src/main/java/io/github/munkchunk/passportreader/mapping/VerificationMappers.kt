package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.model.CertificateDetails
import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.DataGroupHash
import io.github.munkchunk.passportreader.model.VerificationReport
import io.github.munkchunk.passportreader.verification.VerificationState
import java.security.MessageDigest
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import javax.security.auth.x500.X500Principal
import kotlin.math.max

/* --- Certificate helpers --- */

private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

private fun fingerprint(bytes: ByteArray, alg: String) =
    MessageDigest.getInstance(alg).digest(bytes).toHex()

private fun X509Certificate.keySizeBitsOrNull(): Int? = try {
    when (val pk = publicKey) {
        is RSAPublicKey -> pk.modulus.bitLength()
        is ECPublicKey  -> pk.params.curve.field.fieldSize
        else            -> null
    }
} catch (_: Throwable) { null }

/**
 * The C attribute of a distinguished name.
 *
 * Matched against the RFC 2253 rendering with an escape-aware pattern, not a
 * split on commas: that format escapes commas inside values as `\,`, so a
 * naive split mis-parses any DN containing one. javax.naming.ldap.LdapName
 * would parse this properly but is not available on Android.
 */
private fun X500Principal.countryOrNull(): String? =
    getName(X500Principal.RFC2253)
        .let { dn -> Regex("""(?:^|,)\s*C=((?:\\.|[^,\\])*)""").find(dn)?.groupValues?.get(1) }
        ?.replace("\\", "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.uppercase()

private fun X509Certificate.ecCurveNameOrNull(): String? = ecCurveNameOrNull(publicKey.encoded)

/**
 * The standard name of an EC key's curve, such as "brainpoolP256r1", from its
 * DER SubjectPublicKeyInfo; null for a non-EC key or an unrecognised curve.
 *
 * Read from the encoding because ECParameterSpec has no name to give: its
 * toString is an object identity on Android.
 * ICAO certificates often carry the domain parameters explicitly rather than
 * by OID, so explicit parameters are matched against every curve BouncyCastle
 * knows, and the match reported under its canonical name.
 */
internal fun ecCurveNameOrNull(subjectPublicKeyInfo: ByteArray): String? = try {
    val algorithm = SubjectPublicKeyInfo.getInstance(subjectPublicKeyInfo).algorithm
    if (algorithm.algorithm != X9ObjectIdentifiers.id_ecPublicKey) {
        null
    } else {
        when (val parameters = algorithm.parameters?.toASN1Primitive()) {
            is ASN1ObjectIdentifier -> ECNamedCurveTable.getName(parameters)
            is ASN1Sequence -> namedCurveMatching(X9ECParameters.getInstance(parameters))
            else -> null // implicitlyCA: the curve is not in the key at all
        }
    }
} catch (_: Exception) { null }

private fun namedCurveMatching(explicit: X9ECParameters): String? =
    ECNamedCurveTable.getNames().asSequence()
        .map { it as String }
        .firstOrNull { name ->
            val named = ECNamedCurveTable.getByName(name)
            named != null && named.curve == explicit.curve && named.g == explicit.g && named.n == explicit.n
        }
        // Aliases share an OID; report the name that OID is registered under.
        ?.let { name -> ECNamedCurveTable.getOID(name)?.let(ECNamedCurveTable::getName) ?: name }

internal fun X509Certificate.toDetails(role: CertificateRole): CertificateDetails =
    CertificateDetails(
        role               = role,
        subjectDn          = subjectX500Principal.getName(X500Principal.RFC2253),
        issuerDn           = issuerX500Principal.getName(X500Principal.RFC2253),
        subjectCountry     = subjectX500Principal.countryOrNull(),
        serialNumberHex    = serialNumber.toString(16).uppercase(),
        validFromEpochMs   = notBefore.time,
        validUntilEpochMs  = notAfter.time,
        publicKeyAlgorithm = publicKey.algorithm,
        keySizeBits        = keySizeBitsOrNull(),
        ecCurve            = ecCurveNameOrNull(),
        signatureAlgorithm = sigAlgName,
        sha256Fingerprint  = fingerprint(encoded, "SHA-256"),
        sha1Fingerprint    = fingerprint(encoded, "SHA-1"),
    )

/**
 * The role of the certificate at [index] in a chain of [size], document signer
 * first. A chain that reached a trust anchor ends with it, and any between are
 * link certificates. One that did not holds the document signer alone, so the
 * first certificate is always the document signer, never the anchor.
 */
internal fun chainRole(index: Int, size: Int): CertificateRole = when {
    index == 0        -> CertificateRole.DOCUMENT_SIGNER
    index == size - 1 -> CertificateRole.CSCA
    else              -> CertificateRole.LINK
}

/* --- VerificationState -> VerificationReport --- */

internal fun VerificationState.toReport(): VerificationReport {
    val chain = certificateChain?.let { certs ->
        certs.mapIndexed { i, c -> (c as X509Certificate).toDetails(chainRole(i, certs.size)) }
    }

    val hashes = hashResults?.map { (dg, r) ->
        DataGroupHash(
            dataGroup   = dg,
            expectedHex = r.storedHash?.toHex().orEmpty(),
            actualHex   = r.computedHash?.toHex(),
            matches     = r.isMatch,
        )
    }

    return VerificationReport(
        basicAccessControl     = bac,
        pace                   = sac,
        documentSignature      = ds,
        certificateChain       = cs,
        dataGroupHashes        = ht,
        chipAuthentication     = ca,
        activeAuthentication   = aa,
        terminalAuthentication = eac,
        hashes                 = hashes,
        chainCertificates      = chain,
    )
}
