package io.github.munkchunk.passportreader.data

import android.graphics.Bitmap
import android.os.Parcelable
import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.CertificateDetails
import io.github.munkchunk.passportreader.model.DocumentFeatures
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.VerificationReport
import kotlinx.parcelize.Parcelize

/**
 * Everything a read got from the chip, and what could be verified about it.
 *
 * Any field may be null: data groups are optional, and a read that fails part
 * way still returns what it had. Whether the data can be trusted is
 * [verificationReport], not the presence of a field.
 *
 * A plain class rather than a data class: the images and [sodBytes] have no
 * value equality, so a generated `equals` would compare them by reference.
 */
@Parcelize
@Suppress("LongParameterList") // one property per thing a read can produce; it grows with the groups read
class Passport(
    /**
     * The Document Security Object, as raw DER.
     *
     * Bytes rather than a parsed type so consumers are not made to compile
     * against JMRTD. Everything this library derives from it is already here
     * as [documentSigner], [chainCertificates] and [verificationReport]; these are for
     * callers who need to forward the SOd itself, to a server that verifies it
     * independently, for instance.
     */
    var sodBytes: ByteArray? = null,
    var face: Bitmap? = null,
    /**
     * How DG2 encodes the face. Set whenever DG2 was read and held a face
     * record, even if the image then failed to decode - which is when it is
     * most useful to know.
     */
    var faceEncoding: BiometricEncoding? = null,
    /**
     * What each DG2 face record says about its image - colours, landmarks,
     * pose, quality - one entry per face. Empty when DG2 holds no face record
     * or its details could not be read. See [FaceDetails] for which encoding
     * fills which field.
     */
    var faceDetails: List<FaceDetails> = emptyList(),
    var portrait: Bitmap? = null,
    var signature: Bitmap? = null,
    var fingerprints: List<Bitmap>? = emptyList(),
    var personDetails: PersonDetails? = null,
    var additionalPersonDetails: AdditionalPersonDetails? = null,
    var additionalDocumentDetails: AdditionalDocumentDetails? = null,
    /**
     * DG13, exactly as the chip stores it, tag and length included. ICAO
     * leaves its contents to each issuer, so there is no general way to parse
     * it; null when the chip has no DG13 or it could not be read.
     */
    var optionalDetails: ByteArray? = null,
    /** DG16's entries; null when the chip has no DG16 or it could not be read. */
    var personsToNotify: List<PersonToNotify>? = null,
    var features: DocumentFeatures? = DocumentFeatures(),
    var mrz: MrzData? = null,
    /** EF.SOd's document signer certificate, whether or not its chain held. */
    var documentSigner: CertificateDetails? = null,
    /**
     * The chain from the document signer to a trusted CSCA, document signer
     * first and CSCA last. When no chain could be built it holds the document
     * signer alone; it is empty when EF.SOd carries no document signer.
     */
    var chainCertificates: List<CertificateDetails>? = null,
    var verificationReport: VerificationReport? = null,
) : Parcelable
