package com.leadcapture.odoo

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/** Exercises the client over real HTTP against a fake Odoo /jsonrpc endpoint. */
class OdooClientTest {

    private lateinit var server: HttpServer
    private val calls = mutableListOf<JSONObject>() // params of each request
    private var descriptionType = "html"
    private var failAttachments = false
    private var validKey = "secret-key"
    private lateinit var config: OdooConfig

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/jsonrpc") { ex ->
            val req = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            assertEquals("2.0", req.getString("jsonrpc"))
            assertEquals("call", req.getString("method"))
            val params = req.getJSONObject("params")
            synchronized(calls) { calls += params }
            val resp = JSONObject().put("jsonrpc", "2.0").put("id", req.get("id"))
            try {
                resp.put("result", handle(params))
            } catch (e: IllegalStateException) {
                resp.put("error", JSONObject().put("code", 200).put("message", "Odoo Server Error")
                    .put("data", JSONObject().put("message", e.message).put("debug", "Traceback...")))
            }
            val bytes = resp.toString().toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        config = OdooConfig("http://127.0.0.1:${server.address.port}/", "testdb", "sales@example.com", validKey)
    }

    @After
    fun tearDown() = server.stop(0)

    private fun handle(p: JSONObject): Any {
        val args = p.getJSONArray("args")
        return when ("${p.getString("service")}.${p.getString("method")}") {
            "common.version" -> JSONObject().put("server_version", "18.0")
            "common.authenticate" ->
                if (args.getString(0) == "testdb" && args.getString(2) == validKey) 7 else false
            "object.execute_kw" -> {
                check(args.getString(0) == "testdb" && args.getInt(1) == 7 && args.getString(2) == validKey) { "Access Denied" }
                val model = args.getString(3)
                when ("$model.${args.getString(4)}") {
                    "crm.lead.check_access_rights" -> true
                    "crm.lead.fields_get" -> JSONObject().put("description", JSONObject().put("type", descriptionType))
                    "crm.lead.create" -> 42
                    "ir.attachment.create" -> if (failAttachments) error("File too big") else 100 + calls.size
                    "crm.lead.message_post" -> 555
                    else -> error("Unexpected call $model.${args.getString(4)}")
                }
            }
            else -> error("Unknown service")
        }
    }

    private fun executeCalls(model: String, method: String) = synchronized(calls) {
        calls.filter {
            it.getString("method") == "execute_kw" &&
                it.getJSONArray("args").getString(3) == model &&
                it.getJSONArray("args").getString(4) == method
        }
    }

    @Test
    fun submitsLeadWithStandardFieldsAndAttachments() {
        val photo = byteArrayOf(1, 2, 3, 4)
        val audio = byteArrayOf(9, 8, 7)
        val result = OdooClient(config).submitLead(
            LeadInput("Jane Doe", "Acme Ltd", " jane@acme.test ", "+44 1234", "Met at booth.\nWants <demo> & pricing"),
            listOf(Attachment("card.jpg", "image/jpeg", photo), Attachment("note.m4a", "audio/mp4", audio)),
        )

        assertEquals(42, result.leadId)
        assertTrue(result.attachmentErrors.isEmpty())
        assertTrue(result.leadUrl.startsWith("http://127.0.0.1:${server.address.port}/web#id=42&model=crm.lead"))

        val vals = executeCalls("crm.lead", "create").single().getJSONArray("args").getJSONArray(5).getJSONObject(0)
        assertEquals("Acme Ltd - Jane Doe", vals.getString("name"))
        assertEquals("Jane Doe", vals.getString("contact_name"))
        assertEquals("Acme Ltd", vals.getString("partner_name"))
        assertEquals("jane@acme.test", vals.getString("email_from"))
        assertEquals("+44 1234", vals.getString("phone"))
        assertEquals("<p>Met at booth.<br/>Wants &lt;demo&gt; &amp; pricing</p>", vals.getString("description"))
        assertFalse("type must be left to Odoo's default", vals.has("type"))

        val atts = executeCalls("ir.attachment", "create").map {
            it.getJSONArray("args").getJSONArray(5).getJSONObject(0)
        }
        assertEquals(2, atts.size)
        assertEquals("card.jpg", atts[0].getString("name"))
        assertEquals("image/jpeg", atts[0].getString("mimetype"))
        assertEquals("crm.lead", atts[0].getString("res_model"))
        assertEquals(42, atts[0].getInt("res_id"))
        assertEquals(photo.toList(), Base64.getDecoder().decode(atts[0].getString("datas")).toList())
        assertEquals("audio/mp4", atts[1].getString("mimetype"))
        assertEquals(audio.toList(), Base64.getDecoder().decode(atts[1].getString("datas")).toList())

        val post = executeCalls("crm.lead", "message_post").single().getJSONArray("args")
        assertEquals(42, post.getJSONArray(5).getJSONArray(0).getInt(0))
        assertEquals(2, post.getJSONObject(6).getJSONArray("attachment_ids").length())
    }

    @Test
    fun plainTextDescriptionOnOlderOdoo() {
        descriptionType = "text"
        OdooClient(config).submitLead(LeadInput(contactName = "Bob", note = "a < b\nline 2"), emptyList())
        val vals = executeCalls("crm.lead", "create").single().getJSONArray("args").getJSONArray(5).getJSONObject(0)
        assertEquals("a < b\nline 2", vals.getString("description"))
        assertEquals("Bob", vals.getString("name"))
        assertFalse(vals.has("partner_name"))
        assertFalse(vals.has("email_from"))
        assertTrue(executeCalls("crm.lead", "message_post").isEmpty())
    }

    @Test
    fun attachmentFailureStillReportsLead() {
        failAttachments = true
        val r = OdooClient(config).submitLead(LeadInput(companyName = "Acme"),
            listOf(Attachment("card.jpg", "image/jpeg", byteArrayOf(1))))
        assertEquals(42, r.leadId)
        assertEquals(listOf("card.jpg: File too big"), r.attachmentErrors)
    }

    @Test
    fun wrongApiKeyGivesClearError() {
        try {
            OdooClient(config.copy(apiKey = "nope")).submitLead(LeadInput(contactName = "x"), emptyList())
            fail("expected exception")
        } catch (e: OdooException) {
            assertTrue(e.message!!.startsWith("Login failed"))
        }
        assertTrue(executeCalls("crm.lead", "create").isEmpty())
    }

    @Test
    fun serverErrorMessageIsSurfaced() {
        val client = OdooClient(config)
        try {
            client.executeKw(7, "res.partner", "unlink", JSONArray())
            fail("expected exception")
        } catch (e: OdooException) {
            assertEquals("Unexpected call res.partner.unlink", e.message)
            assertEquals("Traceback...", e.debug)
        }
    }

    @Test
    fun testConnectionReportsVersionAndRights() {
        val info = OdooClient(config).testConnection()
        assertEquals(7, info.uid)
        assertEquals("18.0", info.serverVersion)
        assertEquals(true, info.canCreateLeads)
    }

    @Test
    fun unreachableServerGivesReadableError() {
        val port = server.address.port
        server.stop(0)
        try {
            OdooClient(config.copy(url = "http://127.0.0.1:$port")).serverVersion()
            fail("expected exception")
        } catch (e: OdooException) {
            assertTrue(e.message!!.startsWith("Could not reach http://127.0.0.1:$port"))
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() } // for tearDown
    }

    @Test
    fun urlNormalization() {
        assertEquals("https://acme.odoo.com", OdooConfig.normalizeUrl(" acme.odoo.com/ "))
        assertEquals("https://acme.odoo.com", OdooConfig.normalizeUrl("https://acme.odoo.com/odoo/crm"))
        assertEquals("https://acme.odoo.com", OdooConfig.normalizeUrl("https://acme.odoo.com/web#action=1&model=crm.lead"))
        assertEquals("https://odoo.acme.com", OdooConfig.normalizeUrl("https://odoo.acme.com"))
        assertEquals("https://webshop.acme.com", OdooConfig.normalizeUrl("https://webshop.acme.com/web/login?db=x"))
        assertEquals("http://10.0.0.5:8069", OdooConfig.normalizeUrl("http://10.0.0.5:8069/"))
        assertEquals("https://acme.com/erp", OdooConfig.normalizeUrl("https://acme.com/erp/"))
    }

    @Test
    fun leadTitleFallbacks() {
        assertEquals("Acme - Jane", LeadInput("Jane", "Acme").title())
        assertEquals("Jane", LeadInput(contactName = "Jane").title())
        assertEquals("j@x.io", LeadInput(email = "j@x.io").title())
        assertEquals("Lead from mobile app", LeadInput().title())
    }
}
