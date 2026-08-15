package dev.alpine.codex.appserver.runtime

import android.content.Context
import android.system.Os
import dev.alpine.codex.appserver.CodexAppServerErrorCode
import dev.alpine.codex.appserver.CodexAppServerException
import dev.alpine.codex.appserver.pack.CodexAppServerArtifact
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

internal class CodexAppServerLayout(
    val binary: File,
    val home: File,
    val workspace: File,
    val temporary: File,
    val caBundle: File,
    val httpsProxy: String,
)

internal object CodexAppServerLayouts {
    fun prepare(context: Context, httpsProxy: String): CodexAppServerLayout {
        val nativeRoot = File(context.applicationInfo.nativeLibraryDir).canonicalFile
        val binary = File(nativeRoot, CodexAppServerArtifact.NATIVE_LIBRARY_NAME).canonicalFile
        if (binary.parentFile != nativeRoot) {
            throw CodexAppServerException(CodexAppServerErrorCode.ARTIFACT_INVALID)
        }
        verifyBinary(binary)

        val noBackupRoot = context.noBackupFilesDir.canonicalFile
        val home = child(noBackupRoot, "codex-app-server")
        val workspace = child(home, "workspace")
        val temporary = child(home, "tmp")
        listOf(home, workspace, temporary).forEach { directory ->
            if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
                throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_START_FAILED)
            }
            try {
                Os.chmod(directory.absolutePath, 0b111000000)
            } catch (failure: Exception) {
                throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_START_FAILED, failure)
            }
        }
        val caBundle = child(home, "android-system-ca.pem")
        CodexSystemTrustStore.materialize(caBundle)
        return CodexAppServerLayout(binary, home, workspace, temporary, caBundle, httpsProxy)
    }

    private fun child(parent: File, name: String): File {
        val child = File(parent, name).canonicalFile
        if (child.parentFile != parent) {
            throw CodexAppServerException(CodexAppServerErrorCode.PROCESS_START_FAILED)
        }
        return child
    }

    internal fun verifyBinary(binary: File) {
        if (!binary.isFile) {
            throw CodexAppServerException(CodexAppServerErrorCode.ARTIFACT_UNAVAILABLE)
        }
        if (!binary.canExecute() || binary.length() != CodexAppServerArtifact.BINARY_SIZE_BYTES) {
            throw CodexAppServerException(CodexAppServerErrorCode.ARTIFACT_INVALID)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        binary.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        if (actual != CodexAppServerArtifact.BINARY_SHA256) {
            throw CodexAppServerException(CodexAppServerErrorCode.ARTIFACT_INVALID)
        }
    }
}

/**
 * Converts Android's public system trust anchors into the PEM bundle understood by the pinned
 * Linux Codex binary. User-added CAs are intentionally excluded.
 */
internal object CodexSystemTrustStore {
    private val sourceDirectories = listOf(
        File("/apex/com.android.conscrypt/cacerts"),
        File("/system/etc/security/cacerts"),
    )

    fun materialize(destination: File) {
        val factory = try {
            CertificateFactory.getInstance("X.509")
        } catch (failure: Exception) {
            throw trustFailure(failure)
        }
        val certificates = linkedMapOf<String, ByteArray>()
        try {
            sourceDirectories.asSequence()
                .filter(File::isDirectory)
                .flatMap { directory -> directory.listFiles()?.asSequence() ?: emptySequence() }
                .filter(File::isFile)
                .forEach { source ->
                    source.inputStream().buffered().use { input ->
                        factory.generateCertificates(input).forEach { certificate ->
                            val x509 = certificate as? X509Certificate ?: return@forEach
                            val der = x509.encoded
                            certificates.putIfAbsent(sha256(der), der)
                        }
                    }
                }
        } catch (failure: Exception) {
            throw trustFailure(failure)
        }
        if (certificates.isEmpty()) throw trustFailure()
        writePemAtomically(destination, certificates.toSortedMap().values)
    }

    internal fun writePemAtomically(destination: File, certificates: Collection<ByteArray>) {
        if (certificates.isEmpty()) throw trustFailure()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(encodePem(certificates))
                output.fd.sync()
            }
            Os.chmod(temporary.absolutePath, 0b100000000)
            Os.rename(temporary.absolutePath, destination.absolutePath)
            Os.chmod(destination.absolutePath, 0b100000000)
        } catch (failure: Exception) {
            temporary.delete()
            throw trustFailure(failure)
        }
    }

    internal fun encodePem(certificates: Collection<ByteArray>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        certificates.forEach { der ->
            output.write("-----BEGIN CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
            val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte()))
                .encodeToString(der)
            output.write(body.toByteArray(Charsets.US_ASCII))
            output.write("\n-----END CERTIFICATE-----\n".toByteArray(Charsets.US_ASCII))
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun trustFailure(cause: Throwable? = null) =
        CodexAppServerException(CodexAppServerErrorCode.TRUST_STORE_UNAVAILABLE, cause)
}
