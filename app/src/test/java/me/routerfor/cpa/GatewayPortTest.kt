package me.routerfor.cpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GatewayPort reads a scalar out of a file this app also writes by hand and by user edit, so the
 * cases worth pinning are the ones a real config.yaml actually produces: the generated template,
 * a commented-out line, a quoted value, and a document whose server block is not first.
 */
class GatewayPortTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var counter = 0

    // One file per call: TemporaryFolder.newFile rejects a name it already handed out, and a
    // test that needs two documents (a good one and a broken one) must not share a path.
    private fun config(text: String): File =
        tmp.newFile("config-${counter++}.yaml").apply { writeText(text) }

    @Test
    fun readsTheGeneratedTemplate() {
        val file = config(
            """
            config-version: 8

            server:
              host: "127.0.0.1"
              port: 8317

            requests:
              proxy-url: ""
            """.trimIndent()
        )
        assertEquals(8317, GatewayPort.portFromConfig(file))
    }

    @Test
    fun followsAChangedPort() {
        val file = config("server:\n  host: \"127.0.0.1\"\n  port: 9123\n")
        assertEquals(9123, GatewayPort.portFromConfig(file))
    }

    @Test
    fun toleratesAQuotedPort() {
        val file = config("server:\n  port: \"8320\"\n")
        assertEquals(8320, GatewayPort.portFromConfig(file))
    }

    @Test
    fun ignoresACommentedOutPort() {
        val file = config("server:\n  host: 127.0.0.1\n#  port: 9999\n  port: 8317\n")
        assertEquals(8317, GatewayPort.portFromConfig(file))
    }

    @Test
    fun ignoresAPortOutsideTheServerBlock() {
        val file = config("requests:\n  port: 9999\nserver:\n  port: 8317\n")
        assertEquals(8317, GatewayPort.portFromConfig(file))
    }

    @Test
    fun rejectsGarbageInsteadOfGuessing() {
        assertNull(GatewayPort.portFromConfig(config("server:\n  port: eighty\n")))
        assertNull(GatewayPort.portFromConfig(config("server:\n  port: 70000\n")))
        assertNull(GatewayPort.portFromConfig(config("no server block at all: true\n")))
        assertNull(GatewayPort.portFromConfig(File(tmp.root, "missing.yaml")))
    }

    @Test
    fun parsesTheAddressTheEngineReports() {
        assertEquals(8317, GatewayPort.portFromAddress("127.0.0.1:8317"))
        assertEquals(9123, GatewayPort.portFromAddress("0.0.0.0:9123"))
        assertEquals(-1, GatewayPort.portFromAddress("127.0.0.1"))
        assertEquals(-1, GatewayPort.portFromAddress("127.0.0.1:"))
        assertEquals(-1, GatewayPort.portFromAddress(""))
        assertEquals(-1, GatewayPort.portFromAddress("127.0.0.1:abc"))
    }
}
