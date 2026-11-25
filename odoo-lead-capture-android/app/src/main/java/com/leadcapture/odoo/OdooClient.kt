package com.leadcapture.odoo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** Connection settings for one Odoo database. */
data class OdooConfig(
    val url: String,
    val db: String,
    val login: String,
    val apiKey: String,
) {
    val isComplete: Boolean
        get() = url.isNotBlank() && db.isNotBlank() && login.isNotBlank() && apiKey.isNotBlank()

    /** Base URL with a scheme and without a trailing slash. */
    val baseUrl: String
        get() = normalizeUrl(url)

    companion object {
        fun normalizeUrl(raw: String): String {
            var u = raw.trim()
            if (u.isEmpty()) return u
            if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                u = "https://$u"
            }
            // Users often paste a URL copied from the browser, e.g. .../odoo/crm or .../web#...
            u = u.substringBefore('#').substringBefore('?')
            val hostStart = u.indexOf("://") + 3
            val pathStart = u.indexOf('/', hostStart)
            if (pathStart >= 0) {
                val firstSegment = u.substring(pathStart + 1).substringBefore('/')
                if (firstSegment.equals("web", true) || firstSegment.equals("odoo", true)) {
                    u = u.substring(0, pathStart)
                }
            }
            return u.trimEnd('/')
        }
    }
}

/** The lead data captured in the app. All fields are optional. */
data class LeadInput(
    val contactName: String = "",
    val companyName: String = "",
    val email: String = "",
    val phone: String = "",
    val note: String = "",
) {
    /** Odoo requires a lead title (`name`). Build a readable one from what we have. */
    fun title(): String {
        val parts = listOf(companyName, contactName).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isNotEmpty()) return parts.joinToString(" - ")
        return email.trim().ifEmpty { phone.trim() }.ifEmpty { "Lead from mobile app" }
    }

    fun isEmpty(): Boolean =
        listOf(contactName, companyName, email, phone, note).all { it.isBlank() }
}

/** A file to attach to the lead. */
class Attachment(val fileName: String, val mimeType: String, val bytes: ByteArray)

data class SubmitResult(
    val leadId: Int,
    val leadUrl: String,
    /** Human-readable errors for attachments that could not be uploaded. */
    val attachmentErrors: List<String>,
)

data class ConnectionInfo(
    val uid: Int,
    val serverVersion: String,
    /** null when the server could not tell us (e.g. very new/old API). */
    val canCreateLeads: Boolean?,
)

class OdooException(message: String, val debug: String? = null, cause: Throwable? = null) :
    Exception(message, cause)

/** Sends a JSON body to a URL and returns the response body. Swappable for tests. */
fun interface JsonTransport {
    @Throws(IOException::class, OdooException::class)
    fun post(url: String, body: String): String
}

class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 120_000,
) : JsonTransport {
    override fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val hint = when (code) {
                    404 -> " – check the server URL and database name"
                    413 -> " – the attachment is too large for the server"
                    else -> ""
                }
                throw OdooException("Server returned HTTP $code$hint")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Minimal client for Odoo's external JSON-RPC API (`/jsonrpc`), which is available on
 * all Odoo versions from 8 up to current releases, on-premise, Odoo.sh and Odoo Online.
 *
 * Authentication uses the user's login plus an API key (Odoo 14+) or password.
 */
class OdooClient(
    private val config: OdooConfig,
    private val transport: JsonTransport = UrlConnectionTransport(),
) {
    private val requestId = AtomicInteger(1)
    private val endpoint get() = config.baseUrl + "/jsonrpc"

    fun serverVersion(): String {
        val res = call("common", "version", JSONArray())
        return (res as? JSONObject)?.optString("server_version").orEmpty().ifEmpty { "unknown" }
    }

    /** Returns the user id, or throws if the credentials are wrong. */
    fun authenticate(): Int {
        val res = call(
            "common", "authenticate",
            JSONArray().put(config.db).put(config.login).put(config.apiKey).put(JSONObject()),
        )
        return (res as? Number)?.toInt()?.takeIf { it > 0 }
            ?: throw OdooException("Login failed. Check the database name, login and API key.")
    }

    fun executeKw(
        uid: Int,
        model: String,
        method: String,
        args: JSONArray,
        kwargs: JSONObject = JSONObject(),
    ): Any? = call(
        "object", "execute_kw",
        JSONArray()
            .put(config.db).put(uid).put(config.apiKey)
            .put(model).put(method).put(args).put(kwargs),
    )

    fun testConnection(): ConnectionInfo {
        val version = serverVersion()
        val uid = authenticate()
        val canCreate = try {
            executeKw(
                uid, "crm.lead", "check_access_rights",
                JSONArray().put("create"),
                JSONObject().put("raise_exception", false),
            ) as? Boolean
        } catch (e: OdooException) {
            // Method missing (newer API) or CRM not installed: try a cheap read to tell the two apart.
            executeKw(uid, "crm.lead", "search_count", JSONArray().put(JSONArray()))
            null
        }
        return ConnectionInfo(uid, version, canCreate)
    }

    /**
     * Creates a crm.lead and uploads the attachments to it. A lead is still reported as
     * created when an attachment fails, so the user does not submit a duplicate.
     */
    fun submitLead(lead: LeadInput, attachments: List<Attachment>): SubmitResult {
        val uid = authenticate()

        val vals = JSONObject().put("name", lead.title())
        lead.contactName.trim().takeIf { it.isNotEmpty() }?.let { vals.put("contact_name", it) }
        lead.companyName.trim().takeIf { it.isNotEmpty() }?.let { vals.put("partner_name", it) }
        lead.email.trim().takeIf { it.isNotEmpty() }?.let { vals.put("email_from", it) }
        lead.phone.trim().takeIf { it.isNotEmpty() }?.let { vals.put("phone", it) }
        lead.note.trim().takeIf { it.isNotEmpty() }?.let {
            vals.put("description", if (descriptionIsHtml(uid)) textToHtml(it) else it)
        }

        val created = executeKw(uid, "crm.lead", "create", JSONArray().put(vals))
        val leadId = when (created) {
            is Number -> created.toInt()
            is JSONArray -> created.optInt(0)
            else -> 0
        }
        if (leadId <= 0) throw OdooException("Odoo did not return the new lead id (got: $created)")

        val errors = mutableListOf<String>()
        val attachmentIds = JSONArray()
        for (att in attachments) {
            try {
                val attVals = JSONObject()
                    .put("name", att.fileName)
                    .put("datas", Base64.getEncoder().encodeToString(att.bytes))
                    .put("res_model", "crm.lead")
                    .put("res_id", leadId)
                    .put("mimetype", att.mimeType)
                val id = executeKw(uid, "ir.attachment", "create", JSONArray().put(attVals))
                when (id) {
                    is Number -> attachmentIds.put(id.toInt())
                    is JSONArray -> attachmentIds.put(id.optInt(0))
                }
            } catch (e: Exception) {
                errors += "${att.fileName}: ${e.message}"
            }
        }

        // Log a note in the chatter that shows the photo / voice note inline. Not critical:
        // the attachments are already linked to the lead either way.
        if (attachmentIds.length() > 0) {
            try {
                executeKw(
                    uid, "crm.lead", "message_post", JSONArray().put(JSONArray().put(leadId)),
                    JSONObject()
                        .put("body", "Captured with the Odoo Lead Capture app.")
                        .put("attachment_ids", attachmentIds)
                        .put("message_type", "comment")
                        .put("subtype_xmlid", "mail.mt_note"),
                )
            } catch (_: Exception) {
            }
        }

        val url = "${config.baseUrl}/web#id=$leadId&model=crm.lead&view_type=form"
        return SubmitResult(leadId, url, errors)
    }

    /** crm.lead.description is an Html field in recent versions and Text in older ones. */
    private fun descriptionIsHtml(uid: Int): Boolean = try {
        val res = executeKw(
            uid, "crm.lead", "fields_get", JSONArray().put(JSONArray().put("description")),
            JSONObject().put("attributes", JSONArray().put("type")),
        ) as? JSONObject
        res?.optJSONObject("description")?.optString("type") != "text"
    } catch (_: OdooException) {
        true
    }

    private fun call(service: String, method: String, args: JSONArray): Any? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "call")
            .put("id", requestId.getAndIncrement())
            .put("params", JSONObject().put("service", service).put("method", method).put("args", args))

        val raw = try {
            transport.post(endpoint, payload.toString())
        } catch (e: OdooException) {
            throw e
        } catch (e: IOException) {
            throw OdooException("Could not reach ${config.baseUrl}: ${e.message ?: e.javaClass.simpleName}", cause = e)
        }

        val resp = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw OdooException("The server did not answer like Odoo. Check the server URL.", cause = e)
        }

        resp.optJSONObject("error")?.let { err ->
            val data = err.optJSONObject("data")
            val msg = data?.optString("message").orEmpty().ifBlank { err.optString("message", "Unknown Odoo error") }
            throw OdooException(msg, data?.optString("debug"))
        }
        val result = resp.opt("result")
        return if (result == JSONObject.NULL) null else result
    }

    companion object {
        fun textToHtml(text: String): String {
            val escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\r\n", "\n")
                .replace("\n", "<br/>")
            return "<p>$escaped</p>"
        }
    }
}
