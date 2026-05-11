package xyz.mdblab.z80.mdb

/**
 * MDB wire-protocol opcodes the slave needs to recognise. Address bits
 * are folded in (cashless device address = 0x10), so the VMC packets
 * that target us arrive starting with one of these bytes. Spec: MDB 4.2 §7.
 */
object MdbWire {

    // VMC → cashless slave (low 3 bits = command, high 5 bits = address 0x10)
    const val CMD_RESET = 0x10
    const val CMD_SETUP = 0x11
    const val CMD_POLL = 0x12
    const val CMD_VEND = 0x13
    const val CMD_READER = 0x14
    const val CMD_EXPANSION = 0x17

    // SETUP sub-commands
    const val SETUP_CONFIG_DATA = 0x00
    const val SETUP_MAX_MIN_PRICES = 0x01

    // VEND sub-commands
    const val VEND_REQUEST = 0x00
    const val VEND_CANCEL = 0x01
    const val VEND_SUCCESS = 0x02
    const val VEND_FAILURE = 0x03
    const val SESSION_COMPLETE = 0x04
    const val CASH_SALE = 0x05

    // READER sub-commands
    const val READER_DISABLE = 0x00
    const val READER_ENABLE = 0x01
    const val READER_CANCEL = 0x02

    // EXPANSION sub-commands
    const val EXPANSION_REQUEST_ID = 0x00
    const val EXPANSION_DIAGNOSTICS = 0xFF

    // Slave → VMC poll responses (first byte of payload)
    const val POLL_JUST_RESET = 0x00
    const val POLL_READER_CONFIG = 0x01      // followed by 7 config bytes
    const val POLL_DISPLAY_REQUEST = 0x02    // L3
    const val POLL_BEGIN_SESSION = 0x03      // followed by funds (BE u16)
    const val POLL_SESSION_CANCEL_REQ = 0x04
    const val POLL_VEND_APPROVED = 0x05      // followed by amount (BE u16)
    const val POLL_VEND_DENIED = 0x06
    const val POLL_END_SESSION = 0x07
    const val POLL_CANCELLED = 0x08
    const val POLL_PERIPHERAL_ID = 0x09
    const val POLL_OUT_OF_SEQUENCE = 0x0B

    // Special bus tokens
    const val ACK = 0x00
    const val RET = 0xAA
    const val NAK = 0xFF

    /**
     * Append a sum-of-bytes checksum to multi-byte payloads. Single-byte
     * frames (ACK / standalone status) do NOT carry an extra checksum byte —
     * confirmed against the existing fork that already vends on this Z80.
     */
    fun framed(payload: ByteArray): ByteArray {
        var sum = 0
        for (b in payload) sum += b.toInt() and 0xFF
        return payload + (sum and 0xFF).toByte()
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
