"""Regression checks for the original-DEX verifier; optional full APK mutation tests.
Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
"""
import copy
import json
import os
import unittest
from verify_apple_music_profiles import ApkDexContext, DexClass, DexMethod
from verify_apple_music_profile_export import matching_methods, verify_profile


class SignatureTest(unittest.TestCase):
    def test_static_synthetic_and_parameter_order_are_not_interchangeable(self):
        cls = DexClass('LExample;', None, [], 1, methods=[
            DexMethod('f', '', 'V', ['Ljava/lang/String;', 'I'], 1),
            DexMethod('f', '', 'V', ['I', 'Ljava/lang/String;'], 9),
            DexMethod('f', '', 'V', ['Ljava/lang/Object;'], 0x1041),
        ])
        class Context:
            def find_class(self, name):
                return cls if name == 'Example' else None
        target = dict(className='Example', methodName='f', parameterTypeNames=['java.lang.String', 'int'], isStatic=False)
        self.assertEqual(1, len(matching_methods(Context(), target)))
        self.assertEqual([], matching_methods(Context(), dict(target, isStatic=True)))
        bridge = dict(className='Example', methodName='f', parameterTypeNames=['java.lang.Object'])
        self.assertEqual([], matching_methods(Context(), bridge))
        self.assertEqual(1, len(matching_methods(Context(), dict(bridge, includeSynthetic=True))))


@unittest.skipUnless(os.getenv('HLE_APPLE_MUSIC_APK') and os.getenv('HLE_APPLE_PROFILE_EXPORT'),
                     'requires original APK and current Kotlin profile export')
class ActualApkMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ctx = ApkDexContext(os.environ['HLE_APPLE_MUSIC_APK'])
        with open(os.environ['HLE_APPLE_PROFILE_EXPORT']) as stream:
            cls.profile = json.load(stream)
        if isinstance(cls.profile, list):
            cls.profile = next(p for p in cls.profile if p['id'] == 'am-6.5.3-1599')

    def test_current_profile(self):
        self.assertEqual([], verify_profile(self.ctx, self.profile)['errors'])

    def test_stale_and_repurposed_members_are_rejected(self):
        mutations = [
            ('IN_APP_QUEUE_ADAPTER_SUBMIT', None, 'Y8.a'),
            ('APPLE_PLAYER_UTIL_CLASS', None, 'com.apple.android.music.player.O'),
            ('LISTEN_NOW_MODEL', None, 'com.apple.android.music.l1'),
            ('LYRICS_UI_ON_CREATE_VIEW', 'LYRICS_UI_BINDING_FIELD', 'i0'),
            ('LYRICS_UI_ON_CREATE_VIEW', 'LYRICS_UI_VIEW_MODEL_FIELD', 'j1'),
            ('LYRICS_UI_ON_CREATE_VIEW', 'LYRICS_UI_ADAPTER_FIELD', 'k0'),
            ('MEDIA_API_REPOSITORY_HOLDER_CLASS', 'MEDIA_API_DIRECT_QUERY_METHOD', 'B'),
            ('IN_APP_HISTORY_UPDATE', 'QUEUE_HISTORY_ENTRY_CLASS_NAME', 'Z8.d'),
        ]
        for point, member, value in mutations:
            with self.subTest(point=point, member=member):
                profile = copy.deepcopy(self.profile)
                target = profile['hookPoints'][point][0]
                if member:
                    target['runtimeMemberNames'][member] = value
                else:
                    target['className'] = value
                self.assertTrue(verify_profile(self.ctx, profile)['errors'])


if __name__ == '__main__':
    unittest.main()
