package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.MiniXml

/** A control request: which service, which action, and its flat list of named arguments. */
internal class SoapCall(val serviceType: String, val action: String, val arguments: Map<String, String>)

/** UPnP error [code]s: 401 invalid action, 402 invalid args, 501 action failed, 701 transition not available, 714 illegal MIME type. */
internal class SoapFault(val code: Int, description: String) : Exception(description)

internal object Soap {
    private const val SERVICE_PREFIX = "urn:schemas-upnp-org:service:"

    /**
     * [soapAction] is the `SOAPACTION` header (`"urn:…:service:AVTransport:1#Play"`, quotes included). Controllers that leave it
     * out are served from the namespace of the action element.
     */
    fun parse(body: String, soapAction: String?): SoapCall {
        val root = try { MiniXml.parse(body) } catch (error: IllegalArgumentException) { throw SoapFault(402, "Malformed request") }
        if (root.local != "Envelope") throw SoapFault(402, "Not a SOAP envelope")
        val action = root.child("Body")?.children?.firstOrNull() ?: throw SoapFault(402, "Empty SOAP body")
        val header = soapAction?.trim()?.trim('"')?.takeIf { '#' in it }
        val serviceType = header?.substringBefore('#')
            ?: action.attributes.values.firstOrNull { it.startsWith(SERVICE_PREFIX) }
            ?: throw SoapFault(401, "Unknown service")
        val name = header?.substringAfter('#') ?: action.local
        if (name != action.local) throw SoapFault(401, "Action header and body disagree")
        val arguments = linkedMapOf<String, String>()
        for (argument in action.children) arguments[argument.local] = argument.text.trim()
        return SoapCall(serviceType, name, arguments)
    }

    fun escape(text: String): String {
        if (text.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' || it.code < 32 }) return text
        val out = StringBuilder(text.length + 16)
        for (c in text) when {
            c == '&' -> out.append("&amp;")
            c == '<' -> out.append("&lt;")
            c == '>' -> out.append("&gt;")
            c == '"' -> out.append("&quot;")
            c == '\'' -> out.append("&apos;")
            c.code < 32 && c != '\n' && c != '\r' && c != '\t' -> Unit   // not representable in XML 1.0
            else -> out.append(c)
        }
        return out.toString()
    }

    fun response(serviceType: String, action: String, values: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append("Response xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in values) append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')
        append("</u:").append(action).append("Response></s:Body></s:Envelope>")
    }

    fun fault(code: Int, description: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault>")
        append("<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>")
        append("<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>").append(code).append("</errorCode>")
        append("<errorDescription>").append(escape(description)).append("</errorDescription></UPnPError>")
        append("</detail></s:Fault></s:Body></s:Envelope>")
    }
}
