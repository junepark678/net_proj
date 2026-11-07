package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.decodeBase32
import dev.junepark.oniontalkie.identity.validAuthKey
import dev.junepark.oniontalkie.identity.validOnion
import dev.junepark.oniontalkie.tor.TorReply

/** Virtual port every Onion Talkie service listens on; Tor maps it to a local loopback listener. */
const val ONION_VIRTUAL_PORT = 9999

/** The x25519 public key of a paired peer who may reach our service (unpadded base32). */
data class PeerAuthKey(val clientAuthPub: String) {
    init {
        require(validAuthKey(clientAuthPub)) { "Invalid client-auth public key" }
    }

    internal val canonical: String get() = clientAuthPub.uppercase()
}

fun Contact.authKey(): PeerAuthKey = PeerAuthKey(clientAuthPub)

/** The persisted service key as Tor prints and accepts it, e.g. `ED25519-V3:<base64>`. */
internal fun validServiceKeyBlob(blob: String): Boolean {
    if (!blob.startsWith("ED25519-V3:")) return false
    val body = blob.removePrefix("ED25519-V3:")
    // A 64-byte expanded secret key is 88 base64 characters ending in "==".
    return body.length == 88 && body.endsWith("==") &&
        body.dropLast(2).all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
}

/**
 * Formats `ADD_ONION` per the control spec: `KeyType:KeyBlob`, optional Flags, Port, then one
 * ClientAuthV3 per authorized key. [existingKey] reuses a persisted blob and adds `DiscardPK`
 * so Tor does not echo the secret again; otherwise a new key is requested and returned.
 * Keys are deduplicated and sorted so the command is deterministic.
 *
 * Tor serves a v3 service to anyone when no ClientAuthV3 is given, so [authorizedKeys] must not
 * be empty here; [OnionService] substitutes an unusable decoy key instead.
 */
internal fun formatAddOnion(
    existingKey: String?,
    authorizedKeys: List<PeerAuthKey>,
    localPort: Int,
    virtualPort: Int = ONION_VIRTUAL_PORT,
    localHost: String = "127.0.0.1",
): String {
    require(existingKey == null || validServiceKeyBlob(existingKey)) { "Invalid onion service key" }
    require(authorizedKeys.isNotEmpty()) { "A service without authorized clients would be public" }
    require(localPort in 1..65535 && virtualPort in 1..65535) { "Invalid port" }
    require(localHost.isNotEmpty() && localHost.all { it.isLetterOrDigit() || it in ".:-[]" }) { "Invalid host" }
    return buildString {
        append("ADD_ONION ").append(existingKey ?: "NEW:ED25519-V3")
        if (existingKey != null) append(" Flags=DiscardPK")
        append(" Port=").append(virtualPort).append(',').append(localHost).append(':').append(localPort)
        authorizedKeys.map { it.canonical }.distinct().sorted().forEach { append(" ClientAuthV3=").append(it) }
    }
}

/** `serviceId` is the bare 56-character address (no `.onion`). */
internal fun formatDelOnion(serviceId: String): String {
    require(validOnion("$serviceId.onion")) { "Invalid service id" }
    return "DEL_ONION $serviceId"
}

internal class AddOnionResult(val serviceId: String, val privateKey: String?) {
    val onion: String get() = "$serviceId.onion"
    override fun toString() = "AddOnionResult(serviceId=$serviceId, privateKey=<redacted>)"
}

/** Extracts `ServiceID=` and the optional `PrivateKey=` from a 250 reply, validating both. */
internal fun parseAddOnionReply(reply: TorReply): AddOnionResult {
    fun field(name: String) = reply.lines.firstOrNull { it.text.startsWith("$name=") }?.text?.substringAfter('=')
    val serviceId = field("ServiceID") ?: throw IllegalStateException("ADD_ONION reply has no ServiceID")
    check(validOnion("$serviceId.onion")) { "ADD_ONION returned an invalid ServiceID" }
    val key = field("PrivateKey")
    check(key == null || validServiceKeyBlob(key)) { "ADD_ONION returned an invalid PrivateKey" }
    return AddOnionResult(serviceId, key)
}

/** Name of the file Tor reads from `ClientOnionAuthDir` for [peerOnion] (`xxxx.onion` or bare). */
internal fun clientAuthFileName(peerOnion: String): String = "${peerServiceId(peerOnion)}.auth_private"

/** Contents of that file: `<onion without .onion>:descriptor:x25519:<base32 private key>`. */
internal fun clientAuthFileContents(peerOnion: String, privateKey: String): String {
    require(decodeBase32(privateKey)?.size == 32) { "Invalid client-auth private key" }
    return "${peerServiceId(peerOnion)}:descriptor:x25519:${privateKey.uppercase()}\n"
}

private fun peerServiceId(peerOnion: String): String {
    val full = if (peerOnion.endsWith(".onion")) peerOnion else "$peerOnion.onion"
    require(validOnion(full)) { "Invalid peer onion address" }
    return full.removeSuffix(".onion")
}
