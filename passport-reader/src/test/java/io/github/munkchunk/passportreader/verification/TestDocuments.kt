package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.bsi.BSIObjectIdentifiers
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.icao.DataGroupHash
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.icao.LDSSecurityObject
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

/**
 * Invented documents for the verification tests: a CSCA, a document signer
 * and an EF.SOd over a few data groups, all generated here by BouncyCastle.
 *
 * Nothing comes from a real passport, so the tests carry no personal data, and
 * none of it comes from the code under test: these are built with
 * BouncyCastle's CMS generator, not with anything the verifier uses to read.
 */
internal object TestDocuments {

    private val provider = BouncyCastleSupport.provider

    /** The clock the tests run at: inside every "valid" certificate's window. */
    val NOW: Date = date(2026, 9, 25)

    private const val SPECIMEN_LINE_1 = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
    private const val SPECIMEN_LINE_2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    /** DG1 carrying the ICAO 9303 specimen MRZ, and an arbitrary DG2 and DG14. */
    val DATA_GROUPS: Map<Int, ByteArray> = mapOf(
        1 to tlv(0x61, tlv(0x5F1F, (SPECIMEN_LINE_1 + SPECIMEN_LINE_2).toByteArray(Charsets.US_ASCII))),
        2 to tlv(0x75, ByteArray(600) { (it * 7).toByte() }),
        14 to tlv(0x6E, DERSet().encoded),
    )

    fun ecKeys(): KeyPair =
        KeyPairGenerator.getInstance("EC", provider)
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    fun rsaKeys(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA", provider).apply { initialize(bits) }.generateKeyPair()

    class Pki(
        val csca: X509Certificate,
        val cscaKeys: KeyPair,
        val signer: X509Certificate,
        val signerKeys: KeyPair,
    ) {
        fun trustStore(): CscaTrustStore = CscaTrustStore().apply {
            addAnchors(listOf(TrustAnchor(csca, null)))
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(listOf(csca))))
        }
    }

    fun pki(
        signerKeys: KeyPair = ecKeys(),
        signerValidFrom: Date = date(2024, 1, 1),
        signerValidTo: Date = date(2034, 1, 1),
        country: String = "UT",
    ): Pki {
        val cscaParty = Party(X500Name("C=$country,CN=Test CSCA $country"), ecKeys())
        val csca = certificate(cscaParty, cscaParty, date(2020, 1, 1) to date(2040, 1, 1), ca = true)
        val signer = certificate(
            Party(X500Name("C=$country,CN=Test Document Signer $country"), signerKeys),
            cscaParty,
            signerValidFrom to signerValidTo,
            ca = false,
        )
        return Pki(csca, cscaParty.keys, signer, signerKeys)
    }

    private var serial = 1L

    private class Party(val name: X500Name, val keys: KeyPair)

    private fun certificate(subject: Party, issuer: Party, validity: Pair<Date, Date>, ca: Boolean): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuer.name,
            BigInteger.valueOf(serial++),
            validity.first,
            validity.second,
            subject.name,
            subject.keys.public,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
            .addExtension(
                Extension.keyUsage, true,
                KeyUsage(if (ca) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature),
            )
        val algorithm = if (issuer.keys.private.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
        val contentSigner = JcaContentSignerBuilder(algorithm).setProvider(provider).build(issuer.keys.private)
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(contentSigner))
    }

    /**
     * EF.SOd over [groups], signed by [pki]'s document signer.
     *
     * @param algorithms the data group hash and the document signer's signature
     * @param signWith the key actually used to sign; defaults to the signer's
     *   own, and anything else produces a signature that cannot verify
     * @param substituteContent groups whose hashes replace the signed ones
     *   after signing, so the signed `messageDigest` no longer matches
     */
    fun securityObject(
        pki: Pki,
        groups: Map<Int, ByteArray> = DATA_GROUPS,
        algorithms: Algorithms = Algorithms(),
        signWith: PrivateKey = pki.signerKeys.private,
        substituteContent: Map<Int, ByteArray>? = null,
    ): ByteArray {
        val digestAlgorithm = algorithms.digest
        val signedContent = ldsSecurityObject(groups, digestAlgorithm)
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSimpleSignerInfoGeneratorBuilder().setProvider(provider)
                    .build(algorithms.signature, signWith, pki.signer),
            )
            addCertificate(JcaX509CertificateHolder(pki.signer))
        }
        val signed = generator.generate(
            CMSProcessableByteArray(ICAOObjectIdentifiers.id_icao_ldsSecurityObject, signedContent), true,
        )
        var contentInfo = signed.toASN1Structure()
        if (substituteContent != null) {
            val original = SignedData.getInstance(contentInfo.content)
            val swapped = SignedData(
                original.digestAlgorithms,
                ContentInfo(
                    ICAOObjectIdentifiers.id_icao_ldsSecurityObject,
                    DEROctetString(ldsSecurityObject(substituteContent, digestAlgorithm)),
                ),
                original.certificates,
                original.crLs,
                original.signerInfos,
            )
            contentInfo = ContentInfo(contentInfo.contentType, swapped)
        }
        return tlv(0x77, contentInfo.getEncoded("DER"))
    }

    /** id-SecurityObject, EF.CardSecurity's content type (BSI TR-03110-3 A.1.2.2). */
    val CARD_SECURITY_CONTENT = ASN1ObjectIdentifier("0.4.0.127.0.7.3.2.1")

    /**
     * EF.CardSecurity: a CMS SignedData over SecurityInfos holding one
     * ChipAuthenticationPublicKeyInfo per key in [chipKeys], numbered from 1.
     * Unlike EF.SOd it has no LDS tag around it.
     */
    fun cardSecurity(
        pki: Pki,
        chipKeys: List<PublicKey>,
        signWith: PrivateKey = pki.signerKeys.private,
        contentType: ASN1ObjectIdentifier = CARD_SECURITY_CONTENT,
    ): ByteArray {
        val infos = chipKeys.mapIndexed { index, key ->
            ChipAuthenticationPublicKeyInfo(key, BigInteger.valueOf(index + 1L)).derObject.encoded
        }
        return cardSecurityOf(pki, infos, signWith, contentType)
    }

    /** EF.CardSecurity over SecurityInfos given as encoded bytes, as a chip would hold them. */
    fun cardSecurityOf(
        pki: Pki,
        securityInfos: List<ByteArray>,
        signWith: PrivateKey = pki.signerKeys.private,
        contentType: ASN1ObjectIdentifier = CARD_SECURITY_CONTENT,
    ): ByteArray {
        val infos = securityInfos.map { ASN1Primitive.fromByteArray(it) }
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(
                JcaSimpleSignerInfoGeneratorBuilder().setProvider(provider)
                    .build("SHA256withECDSA", signWith, pki.signer),
            )
            addCertificate(JcaX509CertificateHolder(pki.signer))
        }
        val content = DERSet(infos.toTypedArray()).getEncoded("DER")
        return generator.generate(CMSProcessableByteArray(contentType, content), true).encoded
    }

    /** The hash EF.SOd lists data groups under, and the signature over it. */
    class Algorithms(val digest: String = "SHA-256", val signature: String = "SHA256withECDSA")

    private fun ldsSecurityObject(groups: Map<Int, ByteArray>, digestAlgorithm: String): ByteArray {
        val digest = MessageDigest.getInstance(digestAlgorithm, provider)
        val hashes = groups.toSortedMap().map { (number, bytes) ->
            DataGroupHash(number, DEROctetString(digest.digest(bytes)))
        }
        val algorithm = DefaultDigestAlgorithmIdentifierFinder().find(digestAlgorithm)
        return LDSSecurityObject(algorithm, hashes.toTypedArray()).getEncoded("DER")
    }

    /** EF.DG15 holding [keys]' public key. */
    fun dg15(keys: KeyPair): ByteArray = tlv(0x6F, keys.public.encoded)

    /** EF.DG14 with one ActiveAuthenticationInfo naming plain ECDSA with SHA-256. */
    fun dg14WithEcdsaSha256(): ByteArray {
        val info = ASN1EncodableVector().apply {
            add(ICAOObjectIdentifiers.id_icao_aaProtocolObject)
            add(ASN1Integer(1))
            add(BSIObjectIdentifiers.ecdsa_plain_SHA256)
        }
        return tlv(0x6E, DERSet(DERSequence(info)).encoded)
    }

    /**
     * A chip doing RSA Active Authentication as ICAO 9303-11 describes: it
     * signs a nonce M1 of its own followed by the challenge, with ISO/IEC
     * 9796-2 partial recovery, sized so that exactly M1 is recoverable.
     */
    fun rsaChip(keys: KeyPair, digest: () -> Digest = ::SHA1Digest): (ByteArray) -> ByteArray = { challenge ->
        val implicit = digest() is SHA1Digest
        val m1 = ByteArray(recoverableLength(keys, digest, implicit)) { (it + 1).toByte() }
        val signer = ISO9796d2Signer(RSAEngine(), digest(), implicit)
        signer.init(true, PrivateKeyFactory.createKey(keys.private.encoded))
        signer.update(m1, 0, m1.size)
        signer.update(challenge, 0, challenge.size)
        signer.generateSignature()
    }

    /**
     * How many message bytes ISO/IEC 9796-2 recovers for this key and hash,
     * measured by signing an oversized message rather than computed from the
     * standard's formula, so a slip in recalling it cannot creep in.
     */
    private fun recoverableLength(keys: KeyPair, digest: () -> Digest, implicit: Boolean): Int {
        val signer = ISO9796d2Signer(RSAEngine(), digest(), implicit)
        signer.init(true, PrivateKeyFactory.createKey(keys.private.encoded))
        val long = ByteArray(1024)
        signer.update(long, 0, long.size)
        val signature = signer.generateSignature()
        val verifier = ISO9796d2Signer(RSAEngine(), digest(), implicit)
        verifier.init(false, org.bouncycastle.crypto.util.PublicKeyFactory.createKey(keys.public.encoded))
        verifier.updateWithRecoveredMessage(signature)
        return verifier.recoveredMessage.size
    }

    /** A chip doing EC Active Authentication: plain ECDSA with SHA-256 over the challenge. */
    fun ecChip(keys: KeyPair): (ByteArray) -> ByteArray = { challenge ->
        Signature.getInstance("SHA256withPLAIN-ECDSA", provider).run {
            initSign(keys.private)
            update(challenge)
            sign()
        }
    }

    /** A [DocumentSource] answering from memory, recording what it was asked for. */
    class FakeChip(
        private val groups: Map<Int, DataGroupRead>,
        private val activeAuthentication: (ByteArray) -> ByteArray = { throw IllegalStateException("no AA") },
    ) : DocumentSource {
        val requested = mutableListOf<Int>()

        override fun readDataGroup(number: Int): DataGroupRead {
            requested += number
            return groups[number] ?: DataGroupRead.Refused(0x6A82)
        }

        override fun activeAuthenticate(challenge: ByteArray): ByteArray = activeAuthentication(challenge)

        companion object {
            fun holding(groups: Map<Int, ByteArray>) = FakeChip(groups.mapValues { DataGroupRead.Bytes(it.value) })
        }
    }

    fun date(year: Int, month: Int, day: Int): Date =
        Date.from(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC))

    /** BER-TLV with a one- or two-byte tag and a definite length. */
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val tagBytes = if (tag > 0xFF) byteArrayOf((tag shr 8).toByte(), tag.toByte()) else byteArrayOf(tag.toByte())
        val length = when {
            value.size < 0x80 -> byteArrayOf(value.size.toByte())
            value.size <= 0xFF -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return tagBytes + length + value
    }
}
