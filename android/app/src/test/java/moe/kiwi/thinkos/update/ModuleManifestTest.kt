package moe.kiwi.thinkos.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ModuleManifestTest {
    private val fixture = UpdateFixture()
    private val system = UpdateFixture.ec()
    private val backup = UpdateFixture.ec()
    private val payload = "module payload".toByteArray()
    private fun expected(id: String = "thinkos-cast") = UpdateModule(id,
        if (id == "thinkos-immersive") "vector" else "magisk", "v1.1", 2, "installed",
        UpdateCatalog.sha256(payload), payload.size.toLong(), listOf("https://updates.test/payload"), listOf("https://updates.test/manifest"))
    private fun keyDocument() = JSONObject().put("schema", 1)
        .put("active", fixture.key("system", system)).put("backup", fixture.key("system-backup", backup))
    private fun keys() = ModuleManifest.parseKeys(UpdateFixture.bytes(keyDocument()))
    private fun manifest(module: UpdateModule = expected()) = ("thinkos-module 1\nid=${module.id}\nkind=" +
        (if (module.id == "thinkos-immersive") "vector-apk" else "magisk") +
        "\ncode=${module.code}\nversion=${module.version}\nsha256=${module.sha256}\nsize=${module.size}\n").toByteArray()
    private fun read(raw: ByteArray, module: UpdateModule = expected(), pair: KeyPair = system) =
        ModuleManifest.read(raw, fixture.sign(raw, pair), keys(), module)

    @Test fun independentSystemActiveAndBackupAuthenticateExactSnapshots() {
        for (pair in listOf(system, backup)) {
            val raw = manifest(); val sig = fixture.sign(raw, pair)
            val proof = ModuleManifest.read(raw, sig, keys(), expected())
            assertArrayEquals(raw, proof.bytes()); assertArrayEquals(sig, proof.signature())
            val original = raw.copyOf(); raw.fill(0); sig.fill(0)
            proof.bytes().fill(1); proof.signature().fill(1)
            assertArrayEquals(original, proof.bytes()); assertEquals(expected(), proof.module)
        }
        for (pair in listOf(fixture.active, fixture.registry, fixture.test))
            assertThrows(SignedDocument.Rejected::class.java) { read(manifest(), pair = pair) }
    }

    @Test fun onlyTheApkSystemAssetIsOpenedWithoutDebugOrDownloadedFallback() {
        val opened = mutableListOf<String>()
        val keys = ModuleManifest.loadKeyAsset { path -> opened += path; UpdateFixture.bytes(keyDocument()).inputStream() }
        assertEquals(listOf("update/system-keys.json"), opened)
        val raw = manifest(); assertArrayEquals(raw, ModuleManifest.read(raw, fixture.sign(raw, backup), keys, expected()).bytes())
        assertThrows(FileNotFoundException::class.java) { ModuleManifest.loadKeyAsset { throw FileNotFoundException() } }
        assertThrows(IllegalArgumentException::class.java) { ModuleManifest.loadKeyAsset { ByteArray(65537).inputStream() } }
    }

    @Test fun malformedOrReusedKeysCannotPopulateTheSystemRole() {
        val wrong = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val variants = listOf(
            keyDocument().put("backup", fixture.key("system", backup)),
            keyDocument().put("backup", fixture.key("other", system)),
            keyDocument().put("active", fixture.key("system", wrong)),
            keyDocument().put("active", fixture.key("system", system).put("algorithm", "RSA")),
            keyDocument().put("active", fixture.key("system", system).put("spki", Base64.getEncoder().encodeToString(system.public.encoded + 0.toByte()))),
            keyDocument().put("test", fixture.key("test", fixture.test)),
        )
        for (json in variants) assertThrows(Exception::class.java) { ModuleManifest.parseKeys(UpdateFixture.bytes(json)) }
    }

    @Test fun authenticationPrecedesAnyManifestParsing() {
        val invalid = "not a module".toByteArray()
        assertThrows(SignedDocument.Rejected::class.java) { read(invalid, pair = fixture.active) }
        assertThrows(IllegalArgumentException::class.java) { read(invalid) }
        val raw = manifest(); val sig = fixture.sign(raw, system); raw[raw.lastIndex - 1] = '9'.code.toByte()
        assertThrows(SignedDocument.Rejected::class.java) { ModuleManifest.read(raw, sig, keys(), expected()) }
    }

    @Test fun allSevenIdsAndVectorWireKindAgreeWithTheAuthenticatedCatalog() {
        for (id in listOf("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync")) {
            val module = expected(id)
            assertEquals(module, read(manifest(module), module).module)
        }
        val raw = manifest()
        for (module in listOf(expected().copy(id="unknown"), expected().copy(kind="vector"), expected().copy(code=3),
            expected().copy(version="v1.2"), expected().copy(size=1), expected().copy(sha256="0".repeat(64)), expected().copy(policy="unknown")))
            assertThrows(IllegalArgumentException::class.java) { read(raw, module) }
    }

    @Test fun signedNoncanonicalTextAndOverflowAreRejected() {
        val raw = String(manifest(), Charsets.US_ASCII)
        val variants = listOf(raw.dropLast(1), raw+"\n", raw.replace("\n", "\r\n"), raw.replace("code=2", "code=\u00002"),
            raw.replace("code=2", "code=\t2"), raw.replace("code=2", "code=02"), raw.replace("code=2", "code=+2"),
            raw.replace("code=2", "code=0"), raw.replace("code=2", "code=2147483648"),
            raw.replace("size=${payload.size}", "size=16777217"), raw.replace("size=${payload.size}", "size=18446744073709551616"),
            raw.replace("version=v1.1", "version=../escape"), raw.replace("version=v1.1", "version="+"v".repeat(49)),
            raw.replace("id=thinkos-cast\nkind=magisk", "kind=magisk\nid=thinkos-cast"), raw.replace("code=2", "code=2\ncode=2"))
        for (value in variants) assertThrows(IllegalArgumentException::class.java) { read(value.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { read(ByteArray(65537)) }
    }

    @Test fun providerSpecificBerTrailingBytesAndInvalidScalarsAreRejected() {
        val raw = manifest(); val valid = fixture.sign(raw, system)
        fun integer(bytes: ByteArray) = byteArrayOf(2, bytes.size.toByte()) + bytes
        fun der(a: ByteArray, b: ByteArray): ByteArray {
            val body = integer(a) + integer(b)
            return byteArrayOf(0x30, body.size.toByte()) + body
        }
        val order = byteArrayOf(0) + java.math.BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",16).toByteArray().takeLast(32).toByteArray()
        val variants = listOf(valid + 0.toByte(), valid.dropLast(1).toByteArray(), byteArrayOf(0x30, 0x81.toByte()) + valid.drop(1),
            der(byteArrayOf(0), byteArrayOf(1)), der(byteArrayOf(1), byteArrayOf(0)), der(byteArrayOf(0,1), byteArrayOf(1)),
            der(byteArrayOf(0x80.toByte()), byteArrayOf(1)), der(order, byteArrayOf(1)))
        for (signature in variants) assertThrows(IllegalArgumentException::class.java) { ModuleManifest.read(raw, signature, keys(), expected()) }
    }
}
