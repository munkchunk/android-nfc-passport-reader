package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import io.github.munkchunk.passportreader.utils.NfcLog
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathBuilderException
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder

/**
 * The issuer's signature on a file the chip holds, and the certificate chain
 * behind it (ICAO 9303-11 §5.1):
 *
 * - **Document signer (DS)**: the file is a CMS SignedData (RFC 5652) whose
 *   one signature verifies under the document signer certificate it carries.
 *   That includes the `messageDigest` signed attribute matching the content,
 *   so the content itself is covered by the signature.
 * - **Certificate chain (CS)**: the document signer certificate chains to a
 *   CSCA certificate in [trustStore].
 *
 * EF.SOd is checked this way, and so is EF.CardSecurity when PACE-CAM needs
 * the chip's public key from it.
 *
 * @param now the time certificates must be valid at
 */
internal class DocumentSignature(
    private val trustStore: CscaTrustStore,
    private val now: () -> Date = { Date() },
) {
    private val provider = BouncyCastleSupport.provider

    /** The two verdicts, and the chain - document signer first, trust anchor last - they rest on. */
    class Result(val ds: CheckResult, val cs: CheckResult, val chain: List<X509Certificate>)

    /** Checks [signed], read from the chip as [file], which names it in every reason. */
    fun verify(signed: CMSSignedData, file: String): Result {
        val signer = checkDocumentSigner(signed, file)
        val chain = checkChain(signer.certificate)
        return Result(signer.result, chain.result, chain.certificates)
    }

    private class SignerCheck(val result: CheckResult, val certificate: X509Certificate?)

    private fun checkDocumentSigner(signed: CMSSignedData, file: String): SignerCheck {
        // ICAO 9303-10 allows exactly one SignerInfo in EF.SOd, and BSI
        // TR-03110-3 one in EF.CardSecurity.
        val signer = signed.signerInfos.signers.singleOrNull()
        val holder = signer?.let { info ->
            signed.certificates.getMatches(null)
                .filterIsInstance<X509CertificateHolder>()
                .firstOrNull { info.sid.match(it) }
        }
        return when {
            signer == null -> signerFailed("$file must have exactly one signer")
            holder == null -> signerFailed("No document signer certificate in $file")
            else -> verifySignature(signer, holder, file)
        }
    }

    private fun signerFailed(reason: String) = SignerCheck(CheckResult(CheckVerdict.FAILED, reason), null)

    private fun verifySignature(signer: SignerInformation, holder: X509CertificateHolder, file: String): SignerCheck =
        try {
            val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(holder)
            // Built from the public key rather than the certificate so this is
            // purely a signature check: whether the certificate was valid is
            // the chain's question, answered once, against the trust store.
            val verifier = JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(certificate.publicKey)
            val verdict = if (signer.verify(verifier)) {
                CheckResult(CheckVerdict.SUCCEEDED, "$file signature valid")
            } else {
                CheckResult(CheckVerdict.FAILED, "$file signature does not verify")
            }
            SignerCheck(verdict, certificate)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: BouncyCastle reports a messageDigest mismatch,
            // an unknown algorithm and a malformed signature as different
            // exceptions, and all mean the signature does not hold.
            NfcLog.w(TAG, "Document signer check failed for $file", e)
            SignerCheck(CheckResult(CheckVerdict.FAILED, "$file signature does not verify"), null)
        }

    private class ChainCheck(val result: CheckResult, val certificates: List<X509Certificate>)

    /**
     * Builds a path from [documentSigner] to a CSCA trust anchor with the
     * standard PKIX algorithm. The chain returned runs document signer first,
     * trust anchor last.
     *
     * Revocation is not checked: the trust store holds CSCA certificates from
     * a master list, not CRLs. Certificates must be valid at [now], so a
     * document whose signer certificate has expired fails here even if it was
     * valid when the document was signed.
     */
    private fun checkChain(documentSigner: X509Certificate?): ChainCheck = when {
        documentSigner == null ->
            ChainCheck(CheckResult(CheckVerdict.FAILED, "No document signer certificate"), emptyList())
        trustStore.anchors.isEmpty() ->
            ChainCheck(CheckResult(CheckVerdict.FAILED, "No CSCA certificates loaded"), listOf(documentSigner))
        else -> buildChain(documentSigner)
    }

    private fun buildChain(documentSigner: X509Certificate): ChainCheck {
        val alone = listOf(documentSigner)
        val parameters = PKIXBuilderParameters(
            trustStore.anchors,
            X509CertSelector().apply { certificate = documentSigner },
        ).apply {
            isRevocationEnabled = false
            date = now()
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(alone), provider))
            trustStore.certStores.forEach(::addCertStore)
        }
        return try {
            val built = CertPathBuilder.getInstance("PKIX", provider).build(parameters) as PKIXCertPathBuilderResult
            // A PKIX path starts at the target and stops short of the anchor.
            val chain = built.certPath.certificates.filterIsInstance<X509Certificate>() +
                listOfNotNull(built.trustAnchor.trustedCert)
            ChainCheck(CheckResult(CheckVerdict.SUCCEEDED, "Chains to a trusted CSCA"), chain)
        } catch (e: CertPathBuilderException) {
            NfcLog.w(TAG, "No path from the document signer to a trust anchor: ${e.message}")
            ChainCheck(CheckResult(CheckVerdict.FAILED, "No chain to a trusted CSCA"), alone)
        }
    }

    private companion object {
        const val TAG = "PassiveAuthentication"
    }
}
