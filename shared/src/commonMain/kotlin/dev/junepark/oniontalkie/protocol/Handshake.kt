package dev.junepark.oniontalkie.protocol

/**
 * Receives the first frame of an incoming call, which must be a HELLO.
 *
 * If the peer's HELLO carries an unsupported version, answers with `BYE(VERSION)` through
 * [writer] before rethrowing the [ProtocolException] (with [ProtocolError.UNSUPPORTED_VERSION]).
 * Any other failure is rethrown without a reply; in every failure case the caller must close the
 * channel. Sending ACCEPT is left to the caller, which decides whether the call is wanted.
 */
suspend fun readHelloOrReject(reader: FrameReader, writer: FrameWriter): Frame.Hello {
    val frame = try {
        reader.readFrame()
    } catch (e: ProtocolException) {
        if (e.error == ProtocolError.UNSUPPORTED_VERSION) writer.write(Frame.Bye(ByeReason.VERSION))
        throw e
    }
    return frame as? Frame.Hello
        ?: throw ProtocolException(ProtocolError.UNEXPECTED_FRAME, "expected HELLO, got ${frame ?: "end of stream"}")
}
