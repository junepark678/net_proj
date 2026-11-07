package dev.junepark.oniontalkie.onion

import dev.junepark.oniontalkie.identity.Contact
import dev.junepark.oniontalkie.identity.ContactStore
import dev.junepark.oniontalkie.identity.Identity
import dev.junepark.oniontalkie.identity.IdentityStore
import dev.junepark.oniontalkie.identity.decodeBase32
import dev.junepark.oniontalkie.protocol.FrameChannel
import dev.junepark.oniontalkie.tor.hmacSha256
import dev.junepark.oniontalkie.tor.secureControlNonce

/** A call peer whose pairing key was verified before any CallEngine frame is exposed. */
data class AuthenticatedPeer(val contact: Contact, val channel: FrameChannel)

/**
 * Binds Tor's ClientAuthV3 admission to a specific saved contact. Tor only reports that some
 * authorized key connected; this short mutual proof identifies which paired key actually did.
 * The handshake runs before CallEngine starts reading or writing protocol frames.
 */
class AuthenticatedCallHandshake(
    private val identities: IdentityStore,
    private val contacts: ContactStore,
) {
    /** Proves the local client key to [peerOnion] and verifies that the expected peer answered. */
    suspend fun outgoing(peerOnion: String, channel: FrameChannel): FrameChannel {
        val peer = contacts.list().singleOrNull { it.onion == peerOnion }
            ?: throw IllegalArgumentException("Call target is not a saved contact")
        val identity = identities.loadOrCreate { error("Publish the onion service before calling") }
        val secret = sharedSecret(identity, peer.clientAuthPub)
        val clientNonce = secureControlNonce().also { require(it.size == NONCE_BYTES) }
        channel.write(helloPacket(identity.clientAuthPub, clientNonce))
        channel.flush()

        val challenge = readExact(channel, CHALLENGE_BYTES)
        verifyMagic(challenge)
        val serverPublic = challenge.ascii(PUBLIC_OFFSET, PUBLIC_BYTES)
        if (serverPublic != peer.clientAuthPub.uppercase()) fail()
        val serverNonce = challenge.copyOfRange(NONCE_OFFSET, PROOF_OFFSET)
        val actualServerProof = challenge.copyOfRange(PROOF_OFFSET, CHALLENGE_BYTES)
        val expectedServerProof = serverChallengeProof(
            secret, identity.clientAuthPub, peer.clientAuthPub, clientNonce, serverNonce,
            identity.onion, peer.onion,
        )
        if (!constantTimeEquals(actualServerProof, expectedServerProof)) fail()

        val clientProof = clientProof(
            secret, identity.clientAuthPub, clientNonce, serverNonce, identity.onion, peer.onion,
        )
        channel.write(proofPacket(identity.clientAuthPub, clientProof))
        channel.flush()

        val acknowledgement = readExact(channel, PROOF_PACKET_BYTES)
        verifyMagic(acknowledgement)
        if (acknowledgement.ascii(PUBLIC_OFFSET, PUBLIC_BYTES) != peer.clientAuthPub.uppercase()) fail()
        val actualAck = acknowledgement.copyOfRange(PROOF_PACKET_PROOF_OFFSET, PROOF_PACKET_BYTES)
        val expectedAck = serverAcceptProof(
            secret, identity.clientAuthPub, peer.clientAuthPub, clientNonce, serverNonce,
            identity.onion, peer.onion,
        )
        if (!constantTimeEquals(actualAck, expectedAck)) fail()
        return channel
    }

    /** Authenticates an incoming contact and sends the reciprocal proof before returning it. */
    suspend fun incoming(channel: FrameChannel): AuthenticatedPeer {
        val request = readExact(channel, HELLO_BYTES)
        verifyMagic(request)
        val clientPublic = request.ascii(PUBLIC_OFFSET, PUBLIC_BYTES)
        val peer = contacts.list().singleOrNull { it.clientAuthPub.equals(clientPublic, ignoreCase = true) }
            ?: fail()
        val identity = identities.loadOrCreate { error("Publish the onion service before accepting calls") }
        val secret = sharedSecret(identity, clientPublic)
        val clientNonce = request.copyOfRange(NONCE_OFFSET, PROOF_OFFSET)
        val serverNonce = secureControlNonce().also { require(it.size == NONCE_BYTES) }
        val challengeProof = serverChallengeProof(
            secret, clientPublic, identity.clientAuthPub, clientNonce, serverNonce,
            peer.onion, identity.onion,
        )
        channel.write(challengePacket(identity.clientAuthPub, serverNonce, challengeProof))
        channel.flush()

        val response = readExact(channel, PROOF_PACKET_BYTES)
        verifyMagic(response)
        if (response.ascii(PUBLIC_OFFSET, PUBLIC_BYTES) != clientPublic.uppercase()) fail()
        val actualProof = response.copyOfRange(PROOF_PACKET_PROOF_OFFSET, PROOF_PACKET_BYTES)
        val expectedProof = clientProof(
            secret, clientPublic, clientNonce, serverNonce, peer.onion, identity.onion,
        )
        if (!constantTimeEquals(actualProof, expectedProof)) fail()

        channel.write(
            proofPacket(
                identity.clientAuthPub,
                serverAcceptProof(
                    secret, clientPublic, identity.clientAuthPub, clientNonce, serverNonce,
                    peer.onion, identity.onion,
                ),
            ),
        )
        channel.flush()
        return AuthenticatedPeer(peer, channel)
    }

    private fun sharedSecret(identity: Identity, peerPublic: String): ByteArray {
        val privateBytes = decodeBase32(identity.clientAuthPrivate)
            ?: throw IllegalStateException("Saved local client-auth key is invalid")
        val publicBytes = decodeBase32(peerPublic)
            ?: throw IllegalArgumentException("Saved contact client-auth key is invalid")
        val secret = X25519.scalarMult(privateBytes, publicBytes)
        if (secret.all { it == 0.toByte() }) fail()
        return secret
    }

    private fun clientProof(
        secret: ByteArray,
        clientPublic: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientOnion: String,
        serverOnion: String,
    ): ByteArray = hmacSha256(
        secret,
        CLIENT_LABEL + clientPublic.uppercase().encodeToByteArray() + clientNonce + serverNonce +
            clientOnion.encodeToByteArray() + byteArrayOf(0) + serverOnion.encodeToByteArray(),
    )

    private fun serverChallengeProof(
        secret: ByteArray,
        clientPublic: String,
        serverPublic: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientOnion: String,
        serverOnion: String,
    ): ByteArray = hmacSha256(
        secret,
        SERVER_CHALLENGE_LABEL + clientPublic.uppercase().encodeToByteArray() + serverPublic.uppercase().encodeToByteArray() +
            clientNonce + serverNonce + clientOnion.encodeToByteArray() + byteArrayOf(0) + serverOnion.encodeToByteArray(),
    )

    private fun serverAcceptProof(
        secret: ByteArray,
        clientPublic: String,
        serverPublic: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        clientOnion: String,
        serverOnion: String,
    ): ByteArray = hmacSha256(
        secret,
        SERVER_ACCEPT_LABEL + clientPublic.uppercase().encodeToByteArray() + serverPublic.uppercase().encodeToByteArray() +
            clientNonce + serverNonce + clientOnion.encodeToByteArray() + byteArrayOf(0) + serverOnion.encodeToByteArray(),
    )

    private fun helloPacket(publicKey: String, nonce: ByteArray): ByteArray {
        require(publicKey.length == PUBLIC_BYTES && nonce.size == NONCE_BYTES)
        return MAGIC + publicKey.uppercase().encodeToByteArray() + nonce
    }

    private fun challengePacket(publicKey: String, nonce: ByteArray, proof: ByteArray): ByteArray {
        require(publicKey.length == PUBLIC_BYTES && nonce.size == NONCE_BYTES && proof.size == PROOF_BYTES)
        return MAGIC + publicKey.uppercase().encodeToByteArray() + nonce + proof
    }

    private fun proofPacket(publicKey: String, proof: ByteArray): ByteArray {
        require(publicKey.length == PUBLIC_BYTES && proof.size == PROOF_BYTES)
        return MAGIC + publicKey.uppercase().encodeToByteArray() + proof
    }

    private fun verifyMagic(packet: ByteArray) {
        if (!packet.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) fail()
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String {
        val bytes = copyOfRange(offset, offset + length)
        if (bytes.any { (it.toInt() and 0xff) !in 0x21..0x7e }) fail()
        return bytes.decodeToString()
    }

    private suspend fun readExact(channel: FrameChannel, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < result.size) {
            val count = channel.read(result, offset, result.size - offset)
            if (count <= 0 || count > result.size - offset) fail()
            offset += count
        }
        return result
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }

    private fun fail(): Nothing = throw SecurityException("Paired call authentication failed")

    private companion object {
        const val NONCE_BYTES = 32
        const val PUBLIC_BYTES = 52
        const val PROOF_BYTES = 32
        const val PUBLIC_OFFSET = 4
        const val NONCE_OFFSET = PUBLIC_OFFSET + PUBLIC_BYTES
        const val PROOF_OFFSET = NONCE_OFFSET + NONCE_BYTES
        const val HELLO_BYTES = NONCE_OFFSET + NONCE_BYTES
        const val CHALLENGE_BYTES = PROOF_OFFSET + PROOF_BYTES
        const val PROOF_PACKET_BYTES = PUBLIC_OFFSET + PUBLIC_BYTES + PROOF_BYTES
        const val PROOF_PACKET_PROOF_OFFSET = PUBLIC_OFFSET + PUBLIC_BYTES
        val MAGIC = byteArrayOf(0x4f, 0x44, 0x48, 0x01)
        val CLIENT_LABEL = "onion-dial-client-v1\u0000".encodeToByteArray()
        val SERVER_CHALLENGE_LABEL = "onion-dial-server-challenge-v1\u0000".encodeToByteArray()
        val SERVER_ACCEPT_LABEL = "onion-dial-server-accept-v1\u0000".encodeToByteArray()
    }
}
