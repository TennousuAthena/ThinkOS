package moe.kiwi.thinkos.cast.dlna

internal class Arg(val name: String, val variable: String)
internal class Action(val name: String, val inputs: List<Arg>, val outputs: List<Arg>)
internal class StateVar(
    val name: String,
    val type: String = "string",
    val events: Boolean = false,
    val allowed: List<String> = emptyList(),
    val default: String? = null,
    val range: Triple<Int, Int, Int>? = null,
)

/**
 * One UPnP service. The action table is the single source of truth: the SCPD the controller reads is generated from it,
 * and [DlnaRenderer] answers exactly these actions.
 */
internal class ServiceDef(val short: String, val type: String, val id: String, val actions: List<Action>, val variables: List<StateVar>) {
    val scpdPath = "/dlna/$short.xml"
    val controlPath = "/dlna/$short/control"
    val eventPath = "/dlna/$short/event"

    fun scpd(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for (action in actions) {
            append("<action><name>").append(action.name).append("</name>")
            if (action.inputs.isNotEmpty() || action.outputs.isNotEmpty()) {
                append("<argumentList>")
                for ((direction, list) in listOf("in" to action.inputs, "out" to action.outputs)) for (arg in list) {
                    append("<argument><name>").append(arg.name).append("</name><direction>").append(direction)
                    append("</direction><relatedStateVariable>").append(arg.variable).append("</relatedStateVariable></argument>")
                }
                append("</argumentList>")
            }
            append("</action>")
        }
        append("</actionList><serviceStateTable>")
        for (variable in variables) {
            append("<stateVariable sendEvents=\"").append(if (variable.events) "yes" else "no").append("\"><name>").append(variable.name)
            append("</name><dataType>").append(variable.type).append("</dataType>")
            variable.default?.let { append("<defaultValue>").append(Soap.escape(it)).append("</defaultValue>") }
            if (variable.allowed.isNotEmpty()) {
                append("<allowedValueList>")
                variable.allowed.forEach { append("<allowedValue>").append(Soap.escape(it)).append("</allowedValue>") }
                append("</allowedValueList>")
            }
            variable.range?.let { (low, high, step) ->
                append("<allowedValueRange><minimum>$low</minimum><maximum>$high</maximum><step>$step</step></allowedValueRange>")
            }
            append("</stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }
}

internal object Services {
    private const val INSTANCE = "A_ARG_TYPE_InstanceID"
    private fun action(name: String, inputs: List<Pair<String, String>> = emptyList(), outputs: List<Pair<String, String>> = emptyList()) =
        Action(name, inputs.map { Arg(it.first, it.second) }, outputs.map { Arg(it.first, it.second) })
    private val instance = listOf("InstanceID" to INSTANCE)

    val AV_TRANSPORT = ServiceDef("avt", "urn:schemas-upnp-org:service:AVTransport:1", "urn:upnp-org:serviceId:AVTransport", listOf(
        action("SetAVTransportURI", instance + listOf("CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData")),
        action("SetNextAVTransportURI", instance + listOf("NextURI" to "NextAVTransportURI", "NextURIMetaData" to "NextAVTransportURIMetaData")),
        action("GetMediaInfo", instance, listOf("NrTracks" to "NumberOfTracks", "MediaDuration" to "CurrentMediaDuration",
            "CurrentURI" to "AVTransportURI", "CurrentURIMetaData" to "AVTransportURIMetaData", "NextURI" to "NextAVTransportURI",
            "NextURIMetaData" to "NextAVTransportURIMetaData", "PlayMedium" to "PlaybackStorageMedium", "RecordMedium" to "RecordStorageMedium",
            "WriteStatus" to "RecordMediumWriteStatus")),
        action("GetTransportInfo", instance, listOf("CurrentTransportState" to "TransportState", "CurrentTransportStatus" to "TransportStatus",
            "CurrentSpeed" to "TransportPlaySpeed")),
        action("GetPositionInfo", instance, listOf("Track" to "CurrentTrack", "TrackDuration" to "CurrentTrackDuration",
            "TrackMetaData" to "CurrentTrackMetaData", "TrackURI" to "CurrentTrackURI", "RelTime" to "RelativeTimePosition",
            "AbsTime" to "AbsoluteTimePosition", "RelCount" to "RelativeCounterPosition", "AbsCount" to "AbsoluteCounterPosition")),
        action("GetDeviceCapabilities", instance, listOf("PlayMedia" to "PossiblePlaybackStorageMedia", "RecMedia" to "PossibleRecordStorageMedia",
            "RecQualityModes" to "PossibleRecordQualityModes")),
        action("GetTransportSettings", instance, listOf("PlayMode" to "CurrentPlayMode", "RecQualityMode" to "CurrentRecordQualityMode")),
        action("Stop", instance),
        action("Play", instance + listOf("Speed" to "TransportPlaySpeed")),
        action("Pause", instance),
        action("Seek", instance + listOf("Unit" to "A_ARG_TYPE_SeekMode", "Target" to "A_ARG_TYPE_SeekTarget")),
        action("Next", instance),
        action("Previous", instance),
        action("SetPlayMode", instance + listOf("NewPlayMode" to "CurrentPlayMode")),
        action("GetCurrentTransportActions", instance, listOf("Actions" to "CurrentTransportActions")),
    ), listOf(
        StateVar("TransportState", allowed = listOf("STOPPED", "PAUSED_PLAYBACK", "PLAYING", "TRANSITIONING", "NO_MEDIA_PRESENT")),
        StateVar("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")),
        StateVar("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK", "UNKNOWN")),
        StateVar("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED")),
        StateVar("PossiblePlaybackStorageMedia"), StateVar("PossibleRecordStorageMedia"),
        StateVar("CurrentPlayMode", allowed = listOf("NORMAL"), default = "NORMAL"),
        StateVar("TransportPlaySpeed", allowed = listOf("1"), default = "1"),
        StateVar("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED")),
        StateVar("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED")),
        StateVar("PossibleRecordQualityModes"),
        StateVar("NumberOfTracks", "ui4", range = Triple(0, 1, 1)), StateVar("CurrentTrack", "ui4", range = Triple(0, 1, 1)),
        StateVar("CurrentTrackDuration"), StateVar("CurrentMediaDuration"), StateVar("CurrentTrackMetaData"), StateVar("CurrentTrackURI"),
        StateVar("AVTransportURI"), StateVar("AVTransportURIMetaData"), StateVar("NextAVTransportURI"), StateVar("NextAVTransportURIMetaData"),
        StateVar("RelativeTimePosition"), StateVar("AbsoluteTimePosition"),
        StateVar("RelativeCounterPosition", "i4"), StateVar("AbsoluteCounterPosition", "i4"),
        StateVar("CurrentTransportActions"),
        StateVar("LastChange", events = true),
        StateVar("A_ARG_TYPE_SeekMode", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
        StateVar("A_ARG_TYPE_SeekTarget"), StateVar(INSTANCE, "ui4"),
    ))

    val RENDERING_CONTROL = ServiceDef("rcs", "urn:schemas-upnp-org:service:RenderingControl:1", "urn:upnp-org:serviceId:RenderingControl", listOf(
        action("ListPresets", instance, listOf("CurrentPresetNameList" to "PresetNameList")),
        action("SelectPreset", instance + listOf("PresetName" to "A_ARG_TYPE_PresetName")),
        action("GetMute", instance + listOf("Channel" to "A_ARG_TYPE_Channel"), listOf("CurrentMute" to "Mute")),
        action("SetMute", instance + listOf("Channel" to "A_ARG_TYPE_Channel", "DesiredMute" to "Mute")),
        action("GetVolume", instance + listOf("Channel" to "A_ARG_TYPE_Channel"), listOf("CurrentVolume" to "Volume")),
        action("SetVolume", instance + listOf("Channel" to "A_ARG_TYPE_Channel", "DesiredVolume" to "Volume")),
    ), listOf(
        StateVar("PresetNameList"), StateVar("LastChange", events = true),
        StateVar("Mute", "boolean"), StateVar("Volume", "ui2", range = Triple(0, 100, 1)),
        StateVar("A_ARG_TYPE_Channel", allowed = listOf("Master")), StateVar("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults")),
        StateVar(INSTANCE, "ui4"),
    ))

    val CONNECTION_MANAGER = ServiceDef("cms", "urn:schemas-upnp-org:service:ConnectionManager:1", "urn:upnp-org:serviceId:ConnectionManager", listOf(
        action("GetProtocolInfo", outputs = listOf("Source" to "SourceProtocolInfo", "Sink" to "SinkProtocolInfo")),
        action("GetCurrentConnectionIDs", outputs = listOf("ConnectionIDs" to "CurrentConnectionIDs")),
        action("GetCurrentConnectionInfo", listOf("ConnectionID" to "A_ARG_TYPE_ConnectionID"), listOf("RcsID" to "A_ARG_TYPE_RcsID",
            "AVTransportID" to "A_ARG_TYPE_AVTransportID", "ProtocolInfo" to "A_ARG_TYPE_ProtocolInfo",
            "PeerConnectionManager" to "A_ARG_TYPE_ConnectionManager", "PeerConnectionID" to "A_ARG_TYPE_ConnectionID",
            "Direction" to "A_ARG_TYPE_Direction", "Status" to "A_ARG_TYPE_ConnectionStatus")),
    ), listOf(
        StateVar("SourceProtocolInfo", events = true), StateVar("SinkProtocolInfo", events = true), StateVar("CurrentConnectionIDs", events = true),
        StateVar("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
        StateVar("A_ARG_TYPE_ConnectionManager"), StateVar("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
        StateVar("A_ARG_TYPE_ProtocolInfo"), StateVar("A_ARG_TYPE_ConnectionID", "i4"), StateVar("A_ARG_TYPE_AVTransportID", "i4"),
        StateVar("A_ARG_TYPE_RcsID", "i4"),
    ))

    val ALL = listOf(AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)
}

internal object DeviceDescription {
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val PATH = "/dlna/description.xml"

    fun xml(uuid: String, friendlyName: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><device>")
        append("<deviceType>").append(DEVICE_TYPE).append("</deviceType>")
        append("<friendlyName>").append(Soap.escape(friendlyName)).append("</friendlyName>")
        append("<manufacturer>ThinkOS</manufacturer><modelDescription>ThinkOS media renderer</modelDescription>")
        append("<modelName>ThinkOS</modelName><modelNumber>1</modelNumber>")
        append("<UDN>uuid:").append(uuid).append("</UDN><dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>")
        for (service in Services.ALL) {
            append("<service><serviceType>").append(service.type).append("</serviceType><serviceId>").append(service.id)
            append("</serviceId><SCPDURL>").append(service.scpdPath).append("</SCPDURL><controlURL>").append(service.controlPath)
            append("</controlURL><eventSubURL>").append(service.eventPath).append("</eventSubURL></service>")
        }
        append("</serviceList></device></root>")
    }
}
