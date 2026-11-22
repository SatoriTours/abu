"""Verify the Activity contract in debug and release manifests used for packaging."""

import sys
import unittest
from pathlib import Path
from xml.etree import ElementTree

ANDROID = "{http://schemas.android.com/apk/res/android}"
MAIN = "local.abu.MainActivity"
PROBE = "local.abu.miniapps.probe.MiniAppProbeActivity"

def activities(path):
    root = ElementTree.parse(path).getroot()
    package = root.attrib["package"]

    def qualified(name):
        if name.startswith("."):
            return package + name
        return name if "." in name else package + "." + name

    return {
        qualified(activity.attrib[ANDROID + "name"]): activity
        for activity in root.findall("application/activity")
    }

class UiModeManifestTest(unittest.TestCase):
    def test_debug_and_release_main_handle_only_ui_mode(self):
        for variant in ("debug", "release"):
            with self.subTest(variant=variant):
                manifest = Path("app/build/intermediates/packaged_manifests") / variant / (
                    f"process{variant.capitalize()}ManifestForPackage/AndroidManifest.xml"
                )
                entries = activities(manifest)
                self.assertIn(MAIN, entries)
                self.assertEqual(entries[MAIN].get(ANDROID + "configChanges"), "uiMode")
                if variant == "debug":
                    self.assertIn(PROBE, entries)
                    self.assertNotIn("uiMode", entries[PROBE].get(ANDROID + "configChanges", "").split("|"))
                else:
                    self.assertNotIn(PROBE, entries)

if __name__ == "__main__":
    unittest.main(verbosity=2)
