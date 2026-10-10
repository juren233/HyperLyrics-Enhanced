#!/usr/bin/env python3
"""Verify exported CURRENT Kotlin targets against an original APK (not JADX aliases).
Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.

Run AppleMusicProfileBinaryTest with HLE_APPLE_MUSIC_APK set, or supply its JSON export.
Checks every target, inherited descriptors, modifiers and explicit multi-owner member
chains used by settings. This is a binary check, NOT proof of runtime callbacks or UI.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from verify_apple_music_profiles import ApkDexContext, to_dex_type


def manifest_activity_names(apk_path):
    """Read registered components from binary AXML; DEX class existence is insufficient."""
    aapt = shutil.which('aapt')
    roots = [os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')]
    local = Path(__file__).resolve().parents[1] / 'local.properties'
    if local.is_file():
        roots += [line.split('=', 1)[1].strip() for line in local.read_text().splitlines()
                  if line.startswith('sdk.dir=')]
    if not aapt:
        for root in filter(None, roots):
            candidates = sorted(Path(root).glob('build-tools/*/aapt'), reverse=True)
            if candidates:
                aapt = str(candidates[0])
                break
    if not aapt:
        raise RuntimeError('Manifest verification requires Android SDK aapt (ANDROID_HOME or local.properties)')
    xml = subprocess.check_output([aapt, 'dump', 'xmltree', str(apk_path), 'AndroidManifest.xml'], text=True)
    package = re.search(r'\bA: package="([^"]+)"', xml)
    if not package:
        raise ValueError('Binary Manifest package could not be decoded')
    activities = set()
    component_indent = None
    for line in xml.splitlines():
        element = re.match(r'(\s*)E: ([\w-]+)', line)
        if element:
            indent = len(element[1])
            if component_indent is not None and indent <= component_indent:
                component_indent = None
            if element[2] in ('activity', 'activity-alias'):
                component_indent = indent
        elif component_indent is not None:
            name = re.search(r'\bA: android:name\([^)]*\)="([^"]+)"', line)
            if name and len(line) - len(line.lstrip()) == component_indent + 2:
                value = name[1]
                if value.startswith('.'):
                    value = package[1] + value
                elif '.' not in value:
                    value = package[1] + '.' + value
                activities.add(value)
    return activities


def binary_name(descriptor):
    return descriptor[1:-1].replace('/', '.') if descriptor.startswith('L') else descriptor


def lineage(ctx, name):
    seen = set()
    while name and name not in seen:
        seen.add(name)
        cls = ctx.find_class(name)
        if cls is None:
            return
        yield cls
        name = binary_name(cls.superclass_descriptor) if cls.superclass_descriptor else None


def all_methods(ctx, name, include_synthetic=False):
    seen = set()
    for cls in lineage(ctx, name):
        for method in cls.methods:
            if method.name.startswith('<'):
                continue
            if not include_synthetic and (method.is_bridge or method.is_synthetic):
                continue
            key = method.name, tuple(method.param_types)
            if key not in seen:
                seen.add(key)
                yield method


def matching_methods(ctx, target):
    matched = []
    for method in all_methods(ctx, target['className'], target.get('includeSynthetic', False)):
        if target.get('methodName') is not None and method.name != target['methodName']:
            continue
        count = target.get('parameterCount')
        if count is not None and len(method.param_types) != count:
            continue
        params = target.get('parameterTypeNames')
        if params is not None and (len(params) != len(method.param_types) or any(
            wanted is not None and to_dex_type(wanted) != actual
            for wanted, actual in zip(params, method.param_types)
        )):
            continue
        ret = target.get('returnTypeName')
        if ret is not None and method.return_type != to_dex_type(ret):
            continue
        if target.get('isStatic') is not None and method.is_static != target['isStatic']:
            continue
        matched.append(method)
    return matched


def verify_1607_semantics(ctx, points):
    """Reject same-signature targets with different native roles in this original beta."""
    errors = []
    checks = 0

    def require(condition, message):
        nonlocal checks
        checks += 1
        if not condition:
            errors.append(message)

    def references(target):
        methods = matching_methods(ctx, target)
        if len(methods) != 1 or methods[0].dex is None:
            return dict(strings=set(), methods=set(), fields=set(), types=set())
        return methods[0].dex.code_references(methods[0])

    def one(point):
        return points[point][0]

    def method_ref(target):
        methods = matching_methods(ctx, target)
        if len(methods) != 1:
            return None
        method = methods[0]
        return (to_dex_type(target['className']), method.name, tuple(method.param_types), method.return_type)

    routes = [('/v1/catalog/', '/'), ('/v1/catalog/', 'ids['),
              ('/v1/catalog/', '/search'), ('/v1/catalog/', '/search/query'),
              ('/v1/editorial/', '/multiplex/'), ('/v1/editorial/', '/multirooms/')]
    executors = points['MEDIA_API_CATALOG_REQUEST_EXECUTOR']
    require(len(executors) == len(routes), '1607: all six catalogue request paths are required')
    for target, route in zip(executors, routes):
        require(set(route) <= references(target)['strings'],
                f'{target["className"]}#{target["methodName"]}: wrong catalogue route, expected {route}')
    require({'/songs/', '/syllable-lyrics', '/v1/catalog/'} <= references(one('LYRICS_NETWORK_REQUEST'))['strings'],
            '1607: lyrics request does not build the native syllable-lyrics URL')

    menu = one('LYRICS_SOURCE_MENU_CLICK_LISTENER')
    created = references(one('LYRICS_UI_ON_CREATE_VIEW'))
    require(any(owner == to_dex_type(menu['className']) and name == '<init>'
                for owner, name, _, _ in created['methods']),
            '1607: onCreateView does not construct the configured lyrics menu listener')
    fragment = to_dex_type(one('LYRICS_UI_ON_CREATE_VIEW')['className'])
    require(any(f.name == menu['runtimeMemberNames']['LYRICS_SOURCE_MENU_FRAGMENT_FIELD']
                and f.type_descriptor == fragment for c in lineage(ctx, menu['className']) for f in c.fields),
            '1607: lyrics menu capture is not the current lyrics fragment')

    policy = one('COMPOSE_NEVER_EQUAL_POLICY')
    require('NeverEqualPolicy' in references(dict(policy, methodName='toString', parameterTypeNames=[]))['strings'],
            '1607: reused Compose class is not NeverEqualPolicy')
    observe = one('COMPOSE_OBSERVE_AS_STATE')
    require(method_ref(observe) in references(one('LIBRARY_COMPOSE_CONTENT'))['methods'],
            '1607: library content does not call the configured observeAsState')
    calls = references(observe)['methods']
    require(any(name == 'getValue' and not params for _, name, params, _ in calls),
            '1607: observeAsState does not read the current LiveData value')
    helpers = [method for owner, name, params, ret in calls
               if owner == to_dex_type(observe['className']) and ret == to_dex_type(observe['returnTypeName'])
               for method in all_methods(ctx, binary_name(owner))
               if method.name == name and tuple(method.param_types) == params and method.return_type == ret]
    require(any(helper.dex and any(name == 'isInitialized'
                                  for _, name, _, _ in helper.dex.code_references(helper)['methods'])
                for helper in helpers), '1607: observeAsState lost its initialization check')

    preferences = one('APPLE_SHARED_PREFERENCES_CLASS')
    getter = dict(preferences, methodName=preferences['runtimeMemberNames']['LYRICS_PREFERENCES_TRANSLATION_GETTER'],
                  parameterTypeNames=[], returnTypeName='boolean', isStatic=True)
    require(any(owner == to_dex_type(preferences['className']) and name == 't' and typ == 'Ljava/lang/Boolean;'
                for owner, name, typ, _ in references(getter)['fields']),
            'lyrics translation getter reads the wrong preference cache')
    binding = one('IN_APP_ACTION_SHEET_BINDING')
    require(any(f.type_descriptor == 'Lcom/apple/android/music/model/CollectionItemView;'
                for c in lineage(ctx, binding['className']) for f in c.fields),
            'action sheet binding lacks its CollectionItemView field')
    require({'getContentType', 'getImageUrls'} <= {
                name for owner, name, _, _ in references(binding)['methods']
                if owner == 'Lcom/apple/android/music/model/CollectionItemView;'},
            'action sheet binding does not render CollectionItemView artwork and destination')
    for point, key, cache in [('LYRICS_PRONUNCIATION_PREFERENCE', 'k', 's'),
                              ('LYRICS_TRANSLATION_PREFERENCE', 'l', 't')]:
        fields = references(one(point))['fields']
        require(any(owner == to_dex_type(preferences['className']) and name == key
                    and op == 0x62 for owner, name, _, op in fields),
                f'1607: {point} reads the wrong DataStore key')
        require(any(owner == to_dex_type(preferences['className']) and name == cache
                    and typ == 'Ljava/lang/Boolean;' and op == 0x69 for owner, name, typ, op in fields),
                f'1607: {point} writes the wrong preference cache')

    state = one('APP_COMPAT_THEME_STATE')
    mode = (to_dex_type(state['className']), state['runtimeMemberNames']['APP_COMPAT_THEME_MODE_FIELD'], 'I')
    for point in ['ACTIVITY_THEME_CREATE', 'ACTIVITY_THEME_RESTART']:
        require(any((owner, name, typ) == mode and op == 0x60
                    for owner, name, typ, op in references(one(point))['fields']),
                f'1607: {point} does not read the profiled theme mode')
    require(any((owner, name, typ) == mode and op == 0x67
                for owner, name, typ, op in references(one('THEME_MODE_EMIT'))['fields']),
            '1607: theme collector does not write the profiled theme mode')
    return checks, errors


def verify_profile(ctx, profile):
    points = profile['hookPoints']
    errors = []
    checked = 0
    member_checks = 0

    def require(condition, message):
        if not condition:
            errors.append(message)

    def field(owner, name, expected=None):
        nonlocal member_checks
        member_checks += 1
        found = next((f for c in lineage(ctx, owner) for f in c.fields if f.name == name), None)
        require(found is not None, f'{owner}.{name}: missing field')
        if found and expected:
            require(found.type_descriptor == to_dex_type(expected),
                    f'{owner}.{name}: expected {expected}, got {found.type_descriptor}')
        return binary_name(found.type_descriptor) if found else None

    def method(owner, name, params=None, returns=None, static=None):
        nonlocal member_checks
        member_checks += 1
        t = dict(className=owner, methodName=name, parameterTypeNames=params,
                 returnTypeName=returns, isStatic=static)
        found = matching_methods(ctx, t) if owner else []
        require(len(found) == 1, f'{owner}#{name}: expected one matching member, got {len(found)}')
        return found[0] if len(found) == 1 else None

    def one(point):
        return points[point][0]

    # HTTP members belong to the request chain's typed objects, not the interceptor.
    for point in ['CONTENT_HTTP_LOCALIZATION', 'MEDIA_API_AMP_HTTP_INTERCEPTOR']:
        target = one(point)
        names = target['runtimeMemberNames']
        chain = target['parameterTypeNames'][0]
        request = field(chain, names['CONTENT_HTTP_CHAIN_REQUEST_FIELD'])
        field(request, names['CONTENT_HTTP_REQUEST_URL_FIELD'],
              one('LYRICS_COOKIE_JAR')['parameterTypeNames'][0])
        headers = field(request, names['CONTENT_HTTP_REQUEST_HEADERS_FIELD'])
        method(headers, names['CONTENT_HTTP_HEADERS_GET_METHOD'], ['java.lang.String'],
               'java.lang.String', False)
        field(headers, names['CONTENT_HTTP_HEADERS_VALUES_FIELD'], '[Ljava.lang.String;')
        builder_getter = method(request, names['CONTENT_HTTP_REQUEST_NEW_BUILDER_METHOD'], [], static=False)
        if builder_getter:
            builder = binary_name(builder_getter.return_type)
            method(builder, names['CONTENT_HTTP_REQUEST_BUILDER_URL_METHOD'], ['java.lang.String'], 'void', False)
            method(builder, names['CONTENT_HTTP_REQUEST_BUILDER_HEADER_METHOD'],
                   ['java.lang.String', 'java.lang.String'], 'void', False)
            method(builder, names['CONTENT_HTTP_REQUEST_BUILDER_BUILD_METHOD'], [], request, False)
        response = target['returnTypeName']
        field(response, names['CONTENT_HTTP_RESPONSE_STATUS_FIELD'], 'int')
        field(response, names['CONTENT_HTTP_RESPONSE_REQUEST_FIELD'], request)
        field(response, names['CONTENT_HTTP_RESPONSE_HEADERS_FIELD'], headers)

    # Raw 1606 restart-theme fields: one instance snapshot and one static mode.
    for point, member, expected_static in [
        ('ACTIVITY_THEME_CREATE', 'ACTIVITY_THEME_MODE_FIELD', False),
        ('APP_COMPAT_THEME_STATE', 'APP_COMPAT_THEME_MODE_FIELD', True),
    ]:
        if points.get(point):
            target = one(point)
            name = target['runtimeMemberNames'][member]
            field(target['className'], name, 'int')
            matches = [f for cls in lineage(ctx, target['className']) for f in cls.fields if f.name == name]
            require(len(matches) == 1 and bool(matches[0].access_flags & 0x8) == expected_static,
                    f'{target["className"]}.{name}: static/instance mismatch')

    # A reused name is insufficient: f(q.B,int,float)V is not the old f()V resolver.
    custom = one('APPLE_CUSTOM_TEXT_VIEW')
    names = custom['runtimeMemberNames']
    parent = binary_name(ctx.find_class(custom['className']).superclass_descriptor)
    method(parent, names['CUSTOM_TEXT_VIEW_FUTURE_RESOLVE_METHOD'], [], 'void', False)
    require(any(f.type_descriptor == 'Ljava/util/concurrent/Future;'
                for f in ctx.find_class(parent).fields), f'{parent}: Future field missing')
    method(parent, names['CUSTOM_TEXT_VIEW_SET_TYPEFACE_METHOD'],
           ['android.graphics.Typeface', 'int'], 'void', False)
    method(custom['className'], names['CUSTOM_TEXT_VIEW_SET_TEXT_METHOD'],
           ['java.lang.CharSequence', 'android.widget.TextView$BufferType'], 'void', False)
    method(custom['className'], names['CUSTOM_TEXT_VIEW_ON_DRAW_METHOD'],
           ['android.graphics.Canvas'], 'void', False)

    text_utils = one('APPLE_TEXT_STYLE_UTILS')
    factories = [m for m in all_methods(ctx, text_utils['className'])
                 if m.is_static and m.return_type == 'Landroid/graphics/Typeface;'
                 and len(m.param_types) == 4 and m.param_types[0] == 'Landroid/content/Context;']
    require(len(factories) == 1, f'{text_utils["className"]}: Typeface factory ambiguous or missing')
    method(text_utils['className'], text_utils['runtimeMemberNames']['APPLE_TEXT_STYLE_EXPLICIT_TITLE_METHOD'],
           [custom['className'], 'java.lang.String', 'boolean'], 'void', True)
    for target in points['COMPOSE_TEXT_LAYOUT']:
        constructors = [m for m in ctx.find_class(target['className']).methods if m.name == '<init>'
                        and m.param_types and m.param_types[0] == 'Ljava/lang/CharSequence;'
                        and 'Landroid/text/TextPaint;' in m.param_types]
        require(bool(constructors), f'{target["className"]}: no CharSequence/TextPaint constructor')
    word_adapter = one('LYRICS_WORD_RENDER_ADAPTER')['className']
    require(any(m.return_type == 'Landroid/util/ArrayMap;' and m.param_types
                and m.param_types[0] == to_dex_type(one('LYRICS_WORD_VECTOR_CLASS')['className'])
                for m in all_methods(ctx, word_adapter)), f'{word_adapter}: word-measurement methods missing')

    binding = one('PLAYER_SONG_BINDING_EXECUTE')
    names = binding['runtimeMemberNames']
    field(binding['className'], names['PLAYER_SONG_BINDING_PLAYBACK_ITEM_FIELD'],
          'com.apple.android.music.model.PlaybackItem')
    field(binding['className'], names['PLAYER_SONG_BINDING_LYRICS_BUTTON_FIELD'], 'android.widget.ImageView')

    preferred = ctx.find_class(one('LYRICS_PREFERRED_LANGUAGES_REQUEST')['className'])
    require(any(m.name == '<init>' and m.param_types.count('[Ljava/lang/String;') == 2
                for m in preferred.methods), f'{preferred.binary_name}: lyrics-language constructor missing')

    exo = one('EXO_MEDIA_PLAYER')
    names = exo['runtimeMemberNames']
    for key in ['EXO_PLAY_METHOD', 'EXO_PAUSE_METHOD', 'EXO_STOP_METHOD', 'EXO_RELEASE_METHOD']:
        method(exo['className'], names[key], [], 'void', False)
    method(exo['className'], names['EXO_SEEK_METHOD'], ['long'], 'void', False)
    method(exo['className'], names['EXO_CURRENT_POSITION_METHOD'], [], 'long', False)
    # Stable 8.0.x does not consume the 8.1-only network retry member chain.
    # An absent feature is allowed; a partially declared chain must still fail.
    retry_members = {
        'EXO_SHOULD_SKIP_TO_NEXT_ITEM_METHOD', 'EXO_PLAYER_ERROR_METHOD',
        'EXO_EVENT_HANDLER_FIELD', 'EXO_PLAYER_FIELD', 'EXO_PLAYER_RETRY_METHOD',
    }
    if retry_members.intersection(names):
        require(retry_members.issubset(names), f'{exo["className"]}: incomplete network retry member chain')
        if retry_members.issubset(names):
            method(exo['className'], names['EXO_SHOULD_SKIP_TO_NEXT_ITEM_METHOD'],
                   ['java.lang.Exception', 'int', 'com.apple.android.music.playback.player.MediaPlayerContext'], 'boolean', True)
            method(exo['className'], names['EXO_PLAYER_ERROR_METHOD'],
                   ['com.google.android.exoplayer2.ExoPlaybackException'], 'void', False)
            field(exo['className'], names['EXO_EVENT_HANDLER_FIELD'], 'android.os.Handler')
            player = field(exo['className'], names['EXO_PLAYER_FIELD'])
            method(player, names['EXO_PLAYER_RETRY_METHOD'], [], 'void', False)

    for point in ['ATMOS_FORMAT_COPY_WITH_LOUDNESS', 'ATMOS_FORMAT_COPY_WITH_MANIFEST_INFO']:
        target = one(point)
        names = target['runtimeMemberNames']
        for key, typ in [('ATMOS_FORMAT_ID_FIELD', 'java.lang.String'),
                         ('ATMOS_FORMAT_CODECS_FIELD', 'java.lang.String'),
                         ('ATMOS_FORMAT_SAMPLE_MIME_TYPE_FIELD', 'java.lang.String'),
                         ('ATMOS_FORMAT_LOUDNESS_FIELD', 'float'),
                         ('ATMOS_FORMAT_CHANNEL_COUNT_FIELD', 'int'),
                         ('ATMOS_FORMAT_SAMPLE_RATE_FIELD', 'int'),
                         ('ATMOS_FORMAT_BITRATE_FIELD', 'int')]:
            field(target['className'], names[key], typ)
    ludt = one('ATMOS_TRACK_LOUDNESS_METADATA')
    names = ludt['runtimeMemberNames']
    info_array = field(ludt['className'], names['ATMOS_LUDT_TRACK_LOUDNESS_INFO_FIELD'])
    info = binary_name(info_array[1:])
    for key in ['ATMOS_LUDT_LOUDNESS_FIELD', 'ATMOS_LUDT_TRUE_PEAK_FIELD', 'ATMOS_LUDT_SAMPLE_PEAK_FIELD']:
        field(info, names[key], 'float')

    for point, targets in points.items():
        require(bool(targets), f'{point}: empty group')
        for target in targets:
            checked += 1
            owner = target['className']
            require(ctx.find_class(owner) is not None, f'{point}: missing class {owner}')
            if target.get('methodName') is not None:
                found = matching_methods(ctx, target)
                require(len(found) == 1 or bool(found) and target.get('allowFirstMatch', False),
                        f'{point}: {owner}#{target["methodName"]}: {len(found)} matches')
            for key, value in target.get('runtimeMemberNames', {}).items():
                if key.endswith(('_CLASS', '_CLASS_NAME')):
                    require(ctx.find_class(value) is not None, f'{point}.{key}: missing class {value}')

    # MainContentActivity still exists in 1606 DEX but is absent from its Manifest.
    registered = manifest_activity_names(ctx.apk_path)
    for target in points.get('APPLE_MAIN_CONTENT_ACTIVITY', []):
        require(target['className'] in registered,
                f'APPLE_MAIN_CONTENT_ACTIVITY: not a registered Activity in AndroidManifest.xml: {target["className"]}')

    # Verify fields on THEIR ACTUAL OWNERS, not a two-hop bag of matching field letters.
    ui = one('LYRICS_UI_ON_CREATE_VIEW')
    if points.get('ALBUM_COMPOSE_ROW'):
        row = one('ALBUM_COMPOSE_ROW')
        row_owner = row['parameterTypeNames'][1]
        names = row['runtimeMemberNames']
        key_owner = field(row_owner, names['ALBUM_COMPOSE_ROW_KEY_FIELD'], 'D7.q')
        if key_owner:
            field(key_owner, names['ALBUM_COMPOSE_KEY_ID_FIELD'], 'java.lang.String')

    if points.get('RADIO_SEARCH_SESSION'):
        session = one('RADIO_SEARCH_SESSION')
        names = session['runtimeMemberNames']
        kind = names['RADIO_SEARCH_SESSION_KIND_CLASS']
        api = names['RADIO_SEARCH_MEDIA_API_CLASS']
        scope = names['RADIO_SEARCH_SCOPE_CLASS']
        wanted = [to_dex_type(x) for x in [kind, api, scope]]
        require(any(m.name == '<init>' and m.param_types == wanted
                    for m in ctx.find_class(session['className']).methods),
                f'{session["className"]}: isolated search constructor descriptor mismatch')
        start = one('RADIO_SEARCH_START')
        section, catalogue = start['parameterTypeNames'][1:3]
        field(kind, names['RADIO_SEARCH_SESSION_KIND_FIELD'], kind)
        field(section, names['RADIO_SEARCH_ARTISTS_KIND_FIELD'], section)
        field(catalogue, names['RADIO_SEARCH_CATALOG_KIND_FIELD'], catalogue)
        method(scope, names['RADIO_SEARCH_SCOPE_CONTEXT_METHOD'], [], 'fi.e', False)

    names = ui['runtimeMemberNames']
    binding = field(ui['className'], names['LYRICS_UI_BINDING_FIELD'])
    field(binding, names['LYRICS_UI_BINDING_RECYCLER_FIELD'], 'androidx.recyclerview.widget.RecyclerView')
    field(ui['className'], names['LYRICS_UI_VIEW_MODEL_FIELD'],
          'com.apple.android.music.player.viewmodel.PlayerLyricsViewModel')
    adapter = field(ui['className'], names['LYRICS_UI_ADAPTER_FIELD'])
    for item in points['LYRICS_RECYCLER_ADAPTER']:
        require(adapter in [c.binary_name for c in lineage(ctx, item['className'])],
                f'{ui["className"]}: active adapter field does not accept {item["className"]}')
        members = item['runtimeMemberNames']
        lines = method(item['className'], members['LYRICS_ADAPTER_LYRICS_METHOD'], [])
        if lines:
            owner = binary_name(lines.return_type)
            method(owner, members['LYRICS_ADAPTER_LINE_COUNT_METHOD'], [], 'int')
            method(owner, members['LYRICS_ADAPTER_LINE_AT_METHOD'], ['int'])
        method(item['className'], members['LYRICS_ADAPTER_ACTIVE_POSITIONS_METHOD'], [], 'java.util.TreeSet')
        method(item['className'], members['LYRICS_ADAPTER_ITEM_COUNT_METHOD'], [], 'int')
        method(item['className'], members['LYRICS_ADAPTER_ITEM_VIEW_TYPE_METHOD'], ['int'], 'int')
        method(item['className'], members['LYRICS_ADAPTER_NOTIFY_DATA_CHANGED_METHOD'], [], 'void')
        method(item['className'], members['LYRICS_ADAPTER_ACTIVE_LINES_UPDATE_METHOD'],
               ['java.util.List', 'int', '[Landroid.util.Pair;'], 'void')
        for key in ['LYRICS_ADAPTER_TRANSLATION_SELECTED_FIELD', 'LYRICS_ADAPTER_PRONUNCIATION_SELECTED_FIELD']:
            field(item['className'], members[key], 'boolean')

    bindings = points['DATA_BINDING_RUNTIME_CLASSES']
    binding = next(t for t in bindings if t['runtimeMemberNames']['DATA_BINDING_RUNTIME_ROLE'] == 'binding')
    observable = next(t for t in bindings if t['runtimeMemberNames']['DATA_BINDING_RUNTIME_ROLE'] == 'observable')
    names = binding['runtimeMemberNames']
    method(binding['className'], names['DATA_BINDING_REGISTRATION_METHOD'], ['int', observable['className']], 'void')
    method(binding['className'], names['DATA_BINDING_INVALIDATE_METHOD'], [], 'void')
    method(binding['className'], names['DATA_BINDING_EXECUTE_METHOD'], [], 'void')
    method(binding['className'], names['DATA_BINDING_SET_VARIABLE_METHOD'], ['int', 'java.lang.Object'], 'boolean')

    listener = matching_methods(ctx, one('IN_APP_NOW_PLAYING_METADATA_LISTENER'))
    metadata = binary_name(listener[0].param_types[0]) if len(listener) == 1 else None
    queue = one('IN_APP_QUEUE_ADAPTER_SUBMIT')
    names = queue['runtimeMemberNames']
    method(queue['className'], names['QUEUE_ADAPTER_DISPLAYED_ENTRY_METHOD'], ['int'])
    field(queue['className'], names['QUEUE_ADAPTER_SUBMITTED_ENTRIES_FIELD'], 'java.util.ArrayList')
    for key, expected in [('MEDIA3_METADATA_BUNDLE_FIELD', 'android.os.Bundle'),
                          ('MEDIA3_METADATA_TITLE_FIELD', 'java.lang.CharSequence'),
                          ('MEDIA3_METADATA_ARTIST_FIELD', 'java.lang.CharSequence')]:
        field(metadata, names[key], expected)
    util = one('APPLE_PLAYER_UTIL_CLASS')
    for key, returns in [('APPLE_PLAYER_UTIL_CONTAINER_METHOD', 'com.apple.android.music.model.BaseContentItem'),
                         ('APPLE_PLAYER_UTIL_PLAYBACK_ITEM_METHOD', 'com.apple.android.music.model.PlaybackItem')]:
        method(util['className'], util['runtimeMemberNames'][key], [metadata], returns, True)
    history = one('IN_APP_HISTORY_UPDATE')['runtimeMemberNames']['QUEUE_HISTORY_ENTRY_CLASS_NAME']
    field(history, names['QUEUE_ENTRY_ITEM_FIELD'], 'com.apple.android.music.model.CollectionItemView')

    preferences = one('APPLE_SHARED_PREFERENCES_CLASS')
    names = preferences['runtimeMemberNames']
    for key in ['LYRICS_PREFERENCES_TRANSLATION_GETTER', 'LYRICS_PREFERENCES_PRONUNCIATION_GETTER']:
        if key in names:
            method(preferences['className'], names[key], [], 'boolean', True)
    if 'LYRICS_PREFERENCES_STORE_GETTER' in names:
        field(preferences['className'], names['LYRICS_PREFERENCES_PRONUNCIATION_CACHE_FIELD'], 'java.lang.Boolean')
        key_type = field(preferences['className'], names['LYRICS_PREFERENCES_PRONUNCIATION_KEY_FIELD'])
        getter = method(preferences['className'], names['LYRICS_PREFERENCES_STORE_GETTER'], [], static=True)
        if getter:
            method(binary_name(getter.return_type), names['LYRICS_PREFERENCES_STORE_READ_METHOD'],
                   [key_type, 'java.lang.Object'], 'java.lang.Object', False)

    holder = one('MEDIA_API_REPOSITORY_HOLDER_CLASS')
    names = holder['runtimeMemberNames']
    companion = next((f for f in ctx.find_class(holder['className']).fields
                      if f.is_static and f.type_descriptor.endswith('$Companion;')), None)
    require(companion is not None, 'MediaApiRepositoryHolder companion missing')
    if companion:
        getter = method(binary_name(companion.type_descriptor), names['MEDIA_API_HOLDER_GET_MEDIA_API_METHOD'], [])
        if getter:
            method(binary_name(getter.return_type), names['MEDIA_API_DIRECT_QUERY_METHOD'],
                   ['java.lang.String', 'java.util.Map', 'kotlin.coroutines.Continuation'], 'java.lang.Object')
    field(one('MEDIA_API_LOCALIZATION')['className'], names['MEDIA_API_STOREFRONT_FIELD'], 'java.lang.String')

    for point in ['COLLECTION_SURFACE_CLASSES', 'ARTIST_SURFACE_CLASSES']:
        for target in points[point]:
            for key, value in target['runtimeMemberNames'].items():
                if key.endswith('_FIELD'):
                    field(target['className'], value, 'java.lang.String')
                elif key.endswith('_METHOD'):
                    require(any(m.name == value for m in all_methods(ctx, target['className'], True)),
                            f'{target["className"]}#{value}: missing surface member')
    # The old l1 also extends Epoxy; the captured callback owner distinguishes it.
    model = one('LISTEN_NOW_MODEL')
    require(any(f.type_descriptor == 'Lcom/apple/android/music/listennow/ListenNowEpoxyController$R;'
                for c in lineage(ctx, model['className']) for f in c.fields),
            f'{model["className"]}: not the ListenNow callback-bearing model')
    semantic_checks = 0
    if profile['id'] in ('am-7.0.0-beta-1607', 'am-7.0.0-beta-1609'):
        from verify_apple_music_1607_members import verify_consumer_members
        verify_consumer_members(ctx, points, field, method, require,
                                version_code=1609 if profile['id'].endswith('-1609') else 1607)
        semantic_checks, semantic_errors = verify_1607_semantics(ctx, points)
        errors.extend(semantic_errors)
    return dict(profile=profile['id'], groups=len(points), targets=checked, memberChecks=member_checks,
                semanticChecks=semantic_checks, errors=errors,
                scope='DEX descriptors and member chains; not runtime or UI acceptance')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True)
    parser.add_argument('--profiles-json', required=True)
    parser.add_argument('--profile-id', default='am-6.5.3-1599')
    args = parser.parse_args()
    with open(args.profiles_json) as stream:
        profiles = json.load(stream)
    if isinstance(profiles, dict):
        profiles = [profiles]
    profile = next((p for p in profiles if p['id'] == args.profile_id), None)
    if profile is None:
        parser.error(f'Profile {args.profile_id} not in export')
    result = verify_profile(ApkDexContext(args.apk), profile)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if result['errors'] else 0


if __name__ == '__main__':
    sys.exit(main())
