import copy
import unittest

import source_ci


class SourceCiTest(unittest.TestCase):
    def test_source_identity_refusal_reports_only_boolean_checks(self):
        image = {'Id': 'sha256:' + 'a' * 64, 'Os': 'linux', 'Architecture': 'amd64', 'Config': {}}
        selected = {**image, 'Id': 'sha256:' + 'b' * 64}
        with self.assertRaisesRegex(ValueError, r'sameId=False; linux=True; amd64=True; noVolumes=True'):
            source_ci.validate_source_images(image, selected)

    def test_source_identity_still_refuses_every_divergent_guard(self):
        image = {'Id': 'sha256:' + 'a' * 64, 'Os': 'linux', 'Architecture': 'amd64', 'Config': {}}
        source_ci.validate_source_images(image, copy.deepcopy(image))
        for field, value in (('Id', 'sha256:' + 'b' * 64), ('Os', 'other'), ('Architecture', 'arm64'),
                             ('Config', {'Volumes': {'/extra': {}}, 'Env': ['PRIVATE_FIXTURE=must-not-appear']})):
            changed = {**image, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError) as caught:
                source_ci.validate_source_images(changed, image)
            self.assertNotIn('PRIVATE_FIXTURE', str(caught.exception))
            self.assertNotIn('sha256:', str(caught.exception))

    def data(self):
        return {"Id": "a" * 64, "Image": "sha256:" + "b" * 64,
                "Name": "/credpay-source-1-1", "Config": {"Labels": {source_ci.LABEL: "credpay-source-1-1"}},
                "Mounts": [], "State": {"Status": "created", "Running": False, "StartedAt": "0001-01-01T00:00:00Z"}}

    def test_rejects_different_image_or_container_or_host_mount(self):
        for field, value in (("Image", "sha256:" + "c" * 64), ("Id", "d" * 64),
                             ("Mounts", [{"Type": "bind", "Destination": "/workspace"}])):
            data = copy.deepcopy(self.data())
            data[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                getattr(source_ci, "validate_container", lambda *args: None)(data, "a" * 64, "credpay-source-1-1", "sha256:" + "b" * 64)

    def test_rejects_started_or_running_source(self):
        for key, value in (("Status", "exited"), ("Running", True), ("StartedAt", "2026-10-17T05:30:00Z")):
            data = self.data()
            data["State"][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "iniciado"):
                source_ci.assert_never_started(data)

    def test_accepts_created_never_started_source_state(self):
        source_ci.assert_never_started(self.data())


if __name__ == "__main__":
    unittest.main()
