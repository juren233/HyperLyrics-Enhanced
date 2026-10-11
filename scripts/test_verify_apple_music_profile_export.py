"""Regression checks for the original-DEX verifier; optional full APK mutation tests.
Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.
"""
import copy
import json
import os
import struct
import unittest
from verify_apple_music_profiles import ApkDexContext, DexClass, DexMethod, DexParser
from verify_apple_music_profile_export import matching_methods, verify_profile


class SignatureTest(unittest.TestCase):
    def test_code_references_skip_payloads_and_decode_full_method_descriptors(self):
        parser = DexParser.__new__(DexParser)
        parser.strings = ['real', 'payload-is-not-code']
        parser.types = ['LExample;']
        parser.fields_info = [('LExample;', 'I', 'mode')]
        parser.methods_info = [('LExample;', 'load', 'VL', 'V', ['Ljava/lang/String;'])]
        # packed-switch data contains a fake const-string; it must not be scanned as code.
        units = [0x001a, 0, 0x0060, 0, 0x0067, 0, 0x006e, 0, 0,
                 0x001b, 0, 0, 0x0100, 1, 0x001a, 1, 0, 0,
                 0x0077, 0, 0, 0x000e]
        parser.data = bytes(16) + struct.pack('<HHHHII', 1, 0, 0, 0, 0, len(units)) + struct.pack(f'<{len(units)}H', *units)
        method = DexMethod('run', 'V', 'V', [], 1, code_offset=16, dex=parser)
        refs = parser.code_references(method)
        self.assertEqual({'real'}, refs['strings'])
        self.assertEqual({('LExample;', 'load', ('Ljava/lang/String;',), 'V')}, refs['methods'])
        self.assertEqual({('LExample;', 'mode', 'I', 0x60), ('LExample;', 'mode', 'I', 0x67)}, refs['fields'])

    def test_truncated_code_reference_is_rejected(self):
        parser = DexParser.__new__(DexParser)
        parser.data = bytes(16) + struct.pack('<HHHHIIH', 1, 0, 0, 0, 0, 1, 0x1a)
        with self.assertRaisesRegex(ValueError, 'Truncated DEX instruction'):
            parser.code_references(DexMethod('run', 'V', 'V', [], 1, code_offset=16, dex=parser))

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

    def test_partial_network_retry_chain_is_rejected(self):
        profile = copy.deepcopy(self.profile)
        names = profile['hookPoints']['EXO_MEDIA_PLAYER'][0]['runtimeMemberNames']
        for member in list(names):
            if member.startswith(('EXO_SHOULD_SKIP_', 'EXO_PLAYER_', 'EXO_EVENT_HANDLER_')):
                del names[member]
        names['EXO_PLAYER_RETRY_METHOD'] = 'retry'
        self.assertTrue(any('incomplete network retry member chain' in error
                            for error in verify_profile(self.ctx, profile)['errors']))

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


@unittest.skipUnless(os.getenv('HLE_APPLE_MUSIC_1607_APK') and os.getenv('HLE_APPLE_PROFILE_1607_EXPORT'),
                     'requires original 1607 APK and current Kotlin profile export')
class Actual1607ApkMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ctx = ApkDexContext(os.environ['HLE_APPLE_MUSIC_1607_APK'])
        with open(os.environ['HLE_APPLE_PROFILE_1607_EXPORT']) as stream:
            cls.profile = json.load(stream)

    def test_current_1607_profile(self):
        result = verify_profile(self.ctx, self.profile)
        self.assertEqual([], result['errors'])
        self.assertGreater(result['semanticChecks'], 0)

    def test_same_signature_search_station_executor_is_not_catalogue_search(self):
        profile = copy.deepcopy(self.profile)
        search = profile['hookPoints']['MEDIA_API_CATALOG_REQUEST_EXECUTOR'][2]
        search['methodName'] = 'e'
        self.assertEqual(1, len(matching_methods(self.ctx, search)))
        self.assertTrue(any('wrong catalogue route' in e for e in verify_profile(self.ctx, profile)['errors']))

    def test_indirect_consumers_cannot_pass_with_wrong_member_owners_or_signatures(self):
        # These cases used to pass the outer-target verifier. The contracts must
        # follow the actual consumer, not find a matching letter on its Hook owner.
        mutations = [
            ('LYRICS_COOKIE_JAR', 'LYRICS_COOKIE_VALUE_FIELD', 'c', 'kk.l.c'),
            ('IN_APP_QUEUE_ADAPTER_SUBMIT', 'QUEUE_ITEM_METADATA_FIELD', 'b', 'z3.v.b'),
            ('IN_APP_QUEUE_ADAPTER_SUBMIT', 'QUEUE_ITEM_ID_FIELD', 'd', 'z3.v.d'),
            ('COMPOSE_OBSERVE_AS_STATE', 'LIBRARY_COMPOSE_STATE_POLICY_FIELD', 'c', 'z0.y0.c'),
            ('COMPOSE_OBSERVE_AS_STATE', 'LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD', 'getValue', 'z0.y0#getValue'),
            ('LYRICS_GRADIENT_MASK_UPDATE', 'LYRICS_GRADIENT_MASK_POSITIONS_FIELD', 'g', 'Layout$a.g'),
            ('EPOXY_FINAL_BIND', 'EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD', 'missing', 'com.airbnb.epoxy.J#missing'),
            ('LOCAL_MEDIA_PLAYER_CONTROLLER_STATE', 'PLAYBACK_MEDIA_ITEM_DURATION_METHOD', 'getTitle', 'PlayerMediaItem#getTitle'),
            ('LYRICS_VIEW_MODEL_LOAD', 'LYRICS_NATIVE_VECTOR_SIZE_METHOD', 'get', 'Vector$StringVectorNative#get'),
            ('LYRICS_VIEW_MODEL_LOAD', 'LYRICS_NATIVE_SET_TRANSLATION_METHOD', 'getTranslationLanguages', 'SongInfoNative#getTranslationLanguages'),
            ('LYRICS_UI_ON_CREATE_VIEW', 'LYRICS_VIEW_MODEL_TRANSLATION_AVAILABLE_GETTER', 'getTranslationSelectedLiveResult', 'PlayerLyricsViewModel#getTranslationSelectedLiveResult'),
            ('MEDIA_API_REPOSITORY_HOLDER_CLASS', 'CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD', 'getName', 'Attributes#getName'),
        ]
        for point, member, value, expected in mutations:
            with self.subTest(point=point, member=member):
                profile = copy.deepcopy(self.profile)
                profile['hookPoints'][point][0]['runtimeMemberNames'][member] = value
                errors = verify_profile(self.ctx, profile)['errors']
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_previous_beta_identifiers_cannot_pass_as_verified_1607_targets(self):
        mutations = [
            ('APP_COMPAT_THEME_STATE', 'k.f'),
            ('LYRICS_SOURCE_MENU_CLICK_LISTENER', 'com.apple.android.music.player.fragment.b0'),
            ('LYRICS_WORD_RENDER_ADAPTER', 'com.apple.android.music.player.A'),
            ('APPLE_PLAYER_UTIL_CLASS', 'com.apple.android.music.player.P'),
            ('APPLE_TEXT_STYLE_UTILS', 'com.apple.android.music.utils.g1'),
            ('LYRICS_NETWORK_REQUEST', 'x9.Q0'),
            ('COMPOSE_NEVER_EQUAL_POLICY', 'z0.t0'),
        ]
        for point, owner in mutations:
            with self.subTest(point=point):
                profile = copy.deepcopy(self.profile)
                profile['hookPoints'][point][0]['className'] = owner
                self.assertTrue(verify_profile(self.ctx, profile)['errors'])


@unittest.skipUnless(os.getenv('HLE_APPLE_MUSIC_1609_APK') and os.getenv('HLE_APPLE_PROFILE_1609_EXPORT'),
                     'requires original 1609 APK and current Kotlin profile export')
class Actual1609ApkMutationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ctx = ApkDexContext(os.environ['HLE_APPLE_MUSIC_1609_APK'])
        with open(os.environ['HLE_APPLE_PROFILE_1609_EXPORT']) as stream:
            cls.profile = json.load(stream)

    def test_current_profile(self):
        self.assertEqual([], verify_profile(self.ctx, self.profile)['errors'])

    def test_same_signature_different_roles_are_rejected(self):
        mutations = [
            ('MEDIA_API_CATALOG_REQUEST_EXECUTOR', 2, 'methodName', 'i', 'wrong catalogue route'),
            ('IN_APP_ACTION_SHEET_BINDING', 0, 'className', 'q8.t7', 'CollectionItemView'),
        ]
        for point, index, member, value, expected in mutations:
            with self.subTest(point=point):
                profile = copy.deepcopy(self.profile)
                target = profile['hookPoints'][point][index]
                target[member] = value
                self.assertEqual(1, len(matching_methods(self.ctx, target)))
                errors = verify_profile(self.ctx, profile)['errors']
                self.assertTrue(any(expected in error for error in errors), errors)

    def test_unrelated_boolean_preference_getters_are_rejected(self):
        for getter in ['a', 'd']:
            with self.subTest(getter=getter):
                profile = copy.deepcopy(self.profile)
                profile['hookPoints']['APPLE_SHARED_PREFERENCES_CLASS'][0]['runtimeMemberNames'][
                    'LYRICS_PREFERENCES_TRANSLATION_GETTER'] = getter
                errors = verify_profile(self.ctx, profile)['errors']
                self.assertTrue(any('translation getter reads the wrong' in e for e in errors), errors)


if __name__ == '__main__':
    unittest.main()
