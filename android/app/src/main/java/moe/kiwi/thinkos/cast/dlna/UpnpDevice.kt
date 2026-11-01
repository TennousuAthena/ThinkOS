package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.CastPlayer
import moe.kiwi.thinkos.cast.CastState
import java.net.InetAddress
import java.net.URI

/** The HTTP face of the renderer: description, SCPD documents, control and event subscription. */
internal class UpnpDevice(
    val uuid: String,
    private val friendlyName: () -> String,
    player: CastPlayer,
    validate: (URI) -> Unit,
    accept: (InetAddress) -> Boolean,
    private val log: (String) -> Unit = {},
    private val hub: EventHub = EventHub(),
) {
    private val renderer = DlnaRenderer(player, validate) { service, lastChange -> hub.publish(service, lastChange) }
    private val http = HttpServer(accept, ::handle, log)

    fun start(preferredPort: Int, bind: InetAddress? = null): Int = http.start(preferredPort, bind)
    fun stop() = http.stop()
    fun playerChanged(state: CastState) = renderer.playerChanged(state)
    fun subscribers(): Int = hub.count()

    fun handle(request: HttpRequest): HttpResponse {
        val path = request.target.substringBefore('?')
        if (path == DeviceDescription.PATH) {
            if (request.method != "GET" && request.method != "HEAD") return HttpResponse(405)
            return xml(200, DeviceDescription.xml(uuid, friendlyName()))
        }
        val service = Services.ALL.firstOrNull { path == it.scpdPath || path == it.controlPath || path == it.eventPath } ?: return HttpResponse(404)
        return when (path) {
            service.scpdPath -> if (request.method == "GET" || request.method == "HEAD") xml(200, service.scpd()) else HttpResponse(405)
            service.controlPath -> if (request.method == "POST") control(request) else HttpResponse(405)
            else -> event(service, request)
        }
    }

    private fun control(request: HttpRequest): HttpResponse = try {
        val call = Soap.parse(String(request.body, Charsets.UTF_8), request.headers["soapaction"])
        xml(200, renderer.handle(call))
    } catch (fault: SoapFault) {
        xml(500, Soap.fault(fault.code, fault.message.orEmpty()))
    } catch (error: Exception) {
        log("control failed: ${error.javaClass.simpleName}")
        xml(500, Soap.fault(501, "Action failed"))
    }

    private fun event(service: ServiceDef, request: HttpRequest): HttpResponse {
        val sidHeader = request.headers["sid"]
        return when (request.method) {
            "SUBSCRIBE" -> {
                val timeout = request.headers["timeout"]
                if (sidHeader != null) {
                    val granted = hub.renew(sidHeader, timeout) ?: return HttpResponse(412)
                    return subscribed(granted)
                }
                val callback = request.headers["callback"] ?: return HttpResponse(412)
                if (request.headers["nt"] != "upnp:event") return HttpResponse(412)
                val granted = hub.subscribe(service, callback, timeout, request.remote) ?: return HttpResponse(412)
                subscribed(granted, afterSent = { hub.sendInitial(granted.sid, initialEvent(service)) })
            }
            "UNSUBSCRIBE" -> if (sidHeader != null && hub.unsubscribe(sidHeader)) HttpResponse(200) else HttpResponse(412)
            else -> HttpResponse(405)
        }
    }

    private fun initialEvent(service: ServiceDef): List<Pair<String, String>> = when (service) {
        Services.CONNECTION_MANAGER -> listOf("SourceProtocolInfo" to "", "SinkProtocolInfo" to DlnaRenderer.SINK_PROTOCOLS, "CurrentConnectionIDs" to "0")
        else -> listOf("LastChange" to renderer.snapshot(service).orEmpty())
    }

    private fun subscribed(granted: EventHub.Granted, afterSent: (() -> Unit)? = null) =
        HttpResponse(200, listOf("SID" to granted.sid, "TIMEOUT" to "Second-${granted.timeoutSeconds}"), afterSent = afterSent)

    private fun xml(status: Int, body: String) = HttpResponse.text(status, "text/xml; charset=\"utf-8\"", body)
}
