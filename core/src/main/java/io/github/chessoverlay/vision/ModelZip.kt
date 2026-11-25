package io.github.chessoverlay.vision

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The models' zip, unpacked as it is read: its .onnx files into `dir`; returns the SHA-256 of the whole zip. */
object ModelZip {
    fun unpack(input: InputStream, dir: File): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffered = DigestInputStream(input, sha).buffered()
        val zip = ZipInputStream(buffered)
        while (true) {
            val e = zip.nextEntry ?: break
            val name = File(e.name).name
            if (!e.isDirectory && name.endsWith(".onnx")) File(dir, name).outputStream().use { zip.copyTo(it) }
        }
        // The rest of the file (the zip's directory) belongs to the checksum too.
        val rest = ByteArray(64 * 1024)
        while (buffered.read(rest) >= 0) { /* drain */ }
        zip.close()
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
