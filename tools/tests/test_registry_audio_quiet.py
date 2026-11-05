import subprocess
import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tools/tests")]
from registry_device import ExternalInput, parse_audio_quiet, quiet

STREAMS = ("STREAM_SYSTEM", "STREAM_RING", "STREAM_MUSIC", "STREAM_ALARM", "STREAM_NOTIFICATION")


def block(name, current="2 (speaker): 0, 40000000 (default): 5, 4000000 (usb_headset): 6", devices="speaker", muted="true"):
    return "- " + name + ":\n   Muted: " + muted + "\n   Min: 0\n   Max: 7\n   Current: " + current + "\n   Devices: " + devices + "\n"


def audio(**changes):
    return "AudioService state:\nStream volumes:\n" + "".join(block(name, **changes.get(name, {})) for name in STREAMS)


class FakeDevice:
    def __init__(self, dump, fallback=None):
        self.dump = dump
        self.fallback = fallback if fallback is not None else "\n".join("volume is 0" for _ in STREAMS)
        self.calls = []

    def shell(self, *args):
        self.calls.append(args)
        if args == ("dumpsys", "audio"):
            if isinstance(self.dump, Exception):
                raise self.dump
            return self.dump
        if args[:2] == ("sh", "-c"):
            return self.fallback
        raise AssertionError("Unexpected device operation")


class RegistryAudioQuietTest(unittest.TestCase):
    def test_active_zero_ignores_inactive_positive_default_and_usb_indices(self):
        self.assertIs(parse_audio_quiet(audio()), True)
        self.assertIs(parse_audio_quiet(audio(STREAM_MUSIC={"muted": "false"})), True)

    def test_any_active_positive_is_false_even_when_muted(self):
        self.assertIs(parse_audio_quiet(audio(STREAM_ALARM={"current": "2 (speaker): 1, 40000000 (default): 0"})), False)

    def test_all_active_routes_must_be_zero(self):
        self.assertIs(parse_audio_quiet(audio(STREAM_MUSIC={
            "current": "2 (speaker): 0, 4000000 (usb_headset): 0, 40000000 (default): 6",
            "devices": "speaker, usb_headset"})), True)
        self.assertIs(parse_audio_quiet(audio(STREAM_MUSIC={"devices": "speaker, usb_headset"})), False)

    def test_missing_or_duplicate_stream_never_uses_a_partial_decision(self):
        self.assertIsNone(parse_audio_quiet(audio().replace(block("STREAM_NOTIFICATION"), "")))
        self.assertIsNone(parse_audio_quiet(audio() + block("STREAM_MUSIC")))
        positive = audio(STREAM_MUSIC={"current": "2 (speaker): 1"})
        self.assertIsNone(parse_audio_quiet(positive.replace(block("STREAM_NOTIFICATION"), "")))

    def test_missing_duplicate_or_malformed_fields_are_unknown(self):
        source = audio()
        for broken in (source.replace("   Devices: speaker\n", "", 1),
                       source.replace("   Devices: speaker\n", "   Devices: speaker\n   Devices: speaker\n", 1),
                       source.replace("   Current: ", "   Current: 2 (speaker): 0, ", 1),
                       source.replace("Muted: true", "Muted: maybe", 1),
                       source.replace("Max: 7", "Max: invalid", 1),
                       source.replace("STREAM_SYSTEM:", "STREAM_SYSTEM: unexpected", 1)):
            with self.subTest(dump=broken):
                self.assertIsNone(parse_audio_quiet(broken))

    def test_unknown_absent_duplicate_or_inconsistent_routes_fall_back(self):
        variants = ({"devices": "future_route"}, {"devices": "headset"}, {"devices": "default"},
                    {"devices": "speaker, speaker"}, {"devices": "speaker | usb_headset"},
                    {"current": "2 (future_route): 0"}, {"current": "4 (speaker): 0"},
                    {"current": "2 (speaker): 0, 2 (speaker): 0"}, {"current": "2 (speaker): 8"})
        for variant in variants:
            with self.subTest(variant=variant):
                self.assertIsNone(parse_audio_quiet(audio(STREAM_SYSTEM=variant)))

    def test_unrelated_streams_are_not_part_of_the_five_stream_silence_contract(self):
        self.assertIs(parse_audio_quiet(block("STREAM_VOICE_CALL", current="2 (speaker): 7") + audio()), True)

    def test_fast_path_uses_one_read_and_does_not_query_media_for_known_true_or_false(self):
        for dump, expected in ((audio(), True), (audio(STREAM_RING={"current": "2 (speaker): 2"}), False)):
            device = FakeDevice(dump)
            self.assertIs(quiet(device), expected)
            self.assertEqual([("dumpsys", "audio")], device.calls)

    def test_unknown_dump_or_transport_error_keeps_the_original_five_media_queries(self):
        for dump in ("Permission denied", audio(STREAM_MUSIC={"devices": "unknown"}),
                     subprocess.CalledProcessError(1, ["adb", "shell", "dumpsys", "audio"])):
            device = FakeDevice(dump)
            self.assertIs(quiet(device), True)
            self.assertEqual(("dumpsys", "audio"), device.calls[0])
            self.assertEqual(("sh", "-c"), device.calls[1][:2])
            self.assertIn("1 2 3 4 5", device.calls[1][2])
        self.assertIs(quiet(FakeDevice("unknown", "\n".join(["volume is 0"] * 4 + ["volume is 1"]))), False)
        self.assertIs(quiet(FakeDevice("unknown", "\n".join(["volume is 0"] * 4))), False)

    def test_external_input_is_not_caught_or_ignored_by_the_fast_path(self):
        device = FakeDevice(ExternalInput("Human touch"))
        with self.assertRaises(ExternalInput):
            quiet(device)
        self.assertEqual([("dumpsys", "audio")], device.calls)


if __name__ == "__main__":
    unittest.main()
