package moe.kiwi.thinkos.ui

/**
 * What a drag on the picture changes: the device's own brightness and media volume, each as a fraction of its scale. The
 * cinema view only ever sees this, never settings or the audio service (ADR-017).
 */
interface DeviceLevels {
    /** The brightness cap of an attentive display (the `display.brightness_max` setting), 0..1. Setting it applies at once. */
    var brightness: Float
    /** The lowest brightness the setting allows: a drag must not be able to black the screen out. */
    val minimumBrightness: Float
    /** The media volume, the same one the volume keys change. */
    var volume: Float
}
