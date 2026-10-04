"""Additional original-DEX consumer contracts for Apple Music 7.0.0-beta / 1607.
Copyright 2026 juren233. Licensed under the Apache License, Version 2.0.

These owners come from the 1607 binary and the objects consumed by the hooks.
They are deliberately not searched as an unordered bag of nearby field names.
The checks establish member compatibility, not execution or visual acceptance.
"""


def verify_consumer_members(ctx, points, field, method, require):
    def one(point):
        return points[point][0]

    def names(point):
        return one(point)['runtimeMemberNames']

    # rb.k.b calls kk.l$b.b(HttpUrl,String) -> kk.l. The jar's own `a` is an int,
    # so checking cookie members on the Hook owner would give a false positive.
    cookie = names('LYRICS_COOKIE_JAR')
    for key in ['LYRICS_COOKIE_NAME_FIELD', 'LYRICS_COOKIE_VALUE_FIELD']:
        field('kk.l', cookie[key], 'java.lang.String')

    # ea.a.k reads fa.e.b:MediaItem; that item's a/d carry id/MediaMetadata.
    queue = names('IN_APP_QUEUE_ADAPTER_SUBMIT')
    media_item = field('fa.e', queue['QUEUE_ENTRY_ITEM_FIELD'], 'z3.v')
    metadata = one('IN_APP_NOW_PLAYING_METADATA_LISTENER')['parameterTypeNames'][0]
    field(media_item, queue['QUEUE_ITEM_ID_FIELD'], 'java.lang.String')
    field(media_item, queue['QUEUE_ITEM_METADATA_FIELD'], metadata)

    # A0.h.h -> F.c.G(Object) -> z0.y0 extends z0.n1. Observe's declared
    # return z0.p0 is an interface and does not own the mutable policy field.
    state = names('COMPOSE_OBSERVE_AS_STATE')
    policy_type = field('z0.y0', state['LIBRARY_COMPOSE_STATE_POLICY_FIELD'], 'z0.o1')
    policy = ctx.find_class(one('COMPOSE_NEVER_EQUAL_POLICY')['className'])
    require(policy is not None and policy_type is not None and 'L' + policy_type.replace('.', '/') + ';'
            in policy.interfaces, '1607: NeverEqualPolicy does not implement the state policy type')
    method('z0.y0', state['LIBRARY_COMPOSE_STATE_GET_VALUE_METHOD'], [], 'java.lang.Object', False)
    method('z0.y0', state['LIBRARY_COMPOSE_STATE_SET_VALUE_METHOD'], ['java.lang.Object'], 'void', False)

    gradient = one('LYRICS_GRADIENT_MASK_UPDATE')
    for key, typ in [('LYRICS_GRADIENT_MASK_START_CHILD_FIELD', 'int'),
                     ('LYRICS_GRADIENT_MASK_END_CHILD_FIELD', 'int'),
                     ('LYRICS_GRADIENT_MASK_POSITIONS_FIELD', '[F'),
                     ('LYRICS_GRADIENT_MASK_FRACTION_FIELD', 'float')]:
        field(gradient['className'], gradient['runtimeMemberNames'][key], typ)
    br = next(t for t in points['DATA_BINDING_RUNTIME_CLASSES']
              if 'DATA_BINDING_TITLE_VARIABLE_FIELD' in t['runtimeMemberNames'])
    for key in ['DATA_BINDING_TITLE_VARIABLE_FIELD', 'DATA_BINDING_SUBTITLE_VARIABLE_FIELD']:
        field(br['className'], br['runtimeMemberNames'][key], 'int')
    epoxy = one('EPOXY_FINAL_BIND')
    method(epoxy['className'], epoxy['runtimeMemberNames']['EPOXY_FINAL_HOLDER_MODEL_HOLDER_METHOD'], [], static=False)

    playback = names('LOCAL_MEDIA_PLAYER_CONTROLLER_STATE')
    controller = one('LOCAL_MEDIA_PLAYER_CONTROLLER_STATE')['className']
    item_type = 'com.apple.android.music.playback.model.PlayerQueueItem'
    media_type = 'com.apple.android.music.playback.model.PlayerMediaItem'
    method(controller, playback['PLAYBACK_PLAYER_CURRENT_ITEM_METHOD'], [], item_type, False)
    method(item_type, playback['PLAYBACK_QUEUE_ITEM_ITEM_METHOD'], [], media_type, False)
    method(item_type, playback['PLAYBACK_QUEUE_ITEM_ID_METHOD'], [], 'long', False)
    for suffix in ['TITLE', 'ARTIST_NAME', 'GENRE_NAME', 'SUBSCRIPTION_STORE_ID', 'DURATION', 'PERSISTENT_ID']:
        result = 'long' if suffix in ['DURATION', 'PERSISTENT_ID'] else 'java.lang.String'
        method(media_type, playback['PLAYBACK_MEDIA_ITEM_' + suffix + '_METHOD'], [], result, False)

    for point in ['LOCAL_MEDIA_PLAYER_AUDIO_VARIANT_CHANGED', 'DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT',
                  'DEBUG_ATMOS_SV_AUDIO_STREAM_CHANGED']:
        for suffix, typ in [('CODECS', 'java.lang.String'), ('SAMPLE_MIME_TYPE', 'java.lang.String'),
                            ('LOUDNESS', 'float'), ('CHANNEL_COUNT', 'int'),
                            ('SAMPLE_RATE', 'int'), ('BITRATE', 'int')]:
            field('com.google.android.exoplayer2.Format', names(point)['DEBUG_FORMAT_' + suffix + '_FIELD'], typ)
    field('com.google.android.exoplayer2.FormatHolder',
          names('DEBUG_ATMOS_MEDIA_CODEC_INPUT_FORMAT')['DEBUG_FORMAT_HOLDER_FORMAT_FIELD'],
          'com.google.android.exoplayer2.Format')

    ui = names('LYRICS_UI_ON_CREATE_VIEW')
    view_model = one('PLAYER_LYRICS_VIEW_MODEL_CLASS')['className']
    for suffix in ['PRONUNCIATION_SELECTED', 'PRONUNCIATION_AVAILABLE', 'TRANSLATION_SELECTED', 'TRANSLATION_AVAILABLE']:
        result = 'androidx.lifecycle.MutableLiveData' if suffix.endswith('AVAILABLE') else 'androidx.lifecycle.G'
        method(view_model, ui['LYRICS_VIEW_MODEL_' + suffix + '_GETTER'], [], result, False)
    method('com.apple.android.music.library2.LibraryViewModel',
           names('LIBRARY_COMPOSE_CONTENT')['LIBRARY_RECENT_ITEMS_LIVE_RESULT_METHOD'], [], 'androidx.lifecycle.G', False)
    availability = names('PLAYER_LYRICS_AVAILABILITY_CALCULATOR')
    for key in ['PLAYER_LYRICS_ITEM_HAS_LYRICS_METHOD', 'PLAYER_LYRICS_ITEM_HAS_CUSTOM_LYRICS_METHOD']:
        method('com.apple.android.music.model.PlaybackItem', availability[key], [], 'boolean', False)

    # Follow the parser's actual pointer -> native -> vector -> pointer chain,
    # including long vector indexes, native timing ints and pronunciation setters.
    native = names('LYRICS_VIEW_MODEL_LOAD')
    model = 'com.apple.android.music.ttml.javanative.model.'
    string_vector = 'com.apple.android.mediaservices.javanative.common.StringVector$StringVectorNative'

    def native_method(owner, key, params, result):
        method(owner, native[key], params, result, False)

    for kind in ['SongInfo', 'LyricsSection', 'LyricsLine', 'LyricsWord', 'LyricsAgent']:
        owner = model + kind + '$' + kind + 'Native'
        ptr = model + kind + '$' + kind + 'Ptr'
        native_method(ptr, 'LYRICS_NATIVE_POINTER_GET_METHOD', [], owner)
        native_method(ptr, 'LYRICS_NATIVE_POINTER_ADDRESS_METHOD', [], 'long')
        native_method(owner, 'LYRICS_NATIVE_POINTER_ADDRESS_METHOD', [], 'long')
        if kind != 'SongInfo':
            vector = model + kind + 'Vector'
            native_method(vector, 'LYRICS_NATIVE_VECTOR_SIZE_METHOD', [], 'long')
            native_method(vector, 'LYRICS_NATIVE_VECTOR_GET_METHOD', ['long'], ptr)
        if kind in ['LyricsSection', 'LyricsLine', 'LyricsWord']:
            for suffix in ['BEGIN', 'END', 'DURATION']:
                native_method(owner, 'LYRICS_NATIVE_' + suffix + '_METHOD', [], 'int')
            native_method(owner, 'LYRICS_NATIVE_AGENT_METHOD', [], 'java.lang.String')
    song = model + 'SongInfo$SongInfoNative'
    for key, result in [('LYRICS_SONG_ADAM_ID_METHOD', 'long'),
                        ('LYRICS_NATIVE_SONG_QUEUE_ID_METHOD', 'long'),
                        ('LYRICS_NATIVE_DURATION_METHOD', 'int'),
                        ('LYRICS_NATIVE_SONG_SECTIONS_METHOD', model + 'LyricsSectionVector'),
                        ('LYRICS_NATIVE_SONG_AGENTS_METHOD', model + 'LyricsAgentVector'),
                        ('LYRICS_NATIVE_SONG_PRONUNCIATION_LANGUAGES_METHOD', string_vector),
                        ('LYRICS_NATIVE_SONG_TRANSLATION_LANGUAGES_METHOD', string_vector)]:
        native_method(song, key, [], result)
    for suffix in ['ADAM_ID', 'QUEUE_ID']:
        native_method(song, 'LYRICS_NATIVE_SET_' + suffix + '_METHOD', ['long'], 'void')
    for suffix in ['TRANSLATION', 'PRONUNCIATION']:
        for operation in ['SET', 'HAS']:
            native_method(song, 'LYRICS_NATIVE_' + operation + '_' + suffix + '_METHOD', ['java.lang.String'], 'boolean')
    native_method(model + 'LyricsSection$LyricsSectionNative', 'LYRICS_NATIVE_SECTION_LINES_METHOD', [], model + 'LyricsLineVector')
    line = model + 'LyricsLine$LyricsLineNative'
    for suffix in ['LINE', 'TRANSLATION', 'PRONUNCIATION', 'BACKGROUND', 'TRANSLATED_BACKGROUND', 'PRONUNCIATION_BACKGROUND']:
        native_method(line, 'LYRICS_NATIVE_' + suffix + '_TEXT_METHOD', [], 'java.lang.String')
    for suffix in ['WORDS', 'BACKGROUND_WORDS', 'PRONUNCIATION_WORDS', 'PRONUNCIATION_BACKGROUND_WORDS']:
        native_method(line, 'LYRICS_NATIVE_' + suffix + '_METHOD', [], model + 'LyricsWordVector')
        if 'BACKGROUND' in suffix:
            native_method(line, 'LYRICS_NATIVE_' + suffix + '_METHOD', ['boolean'], model + 'LyricsWordVector')
    word = model + 'LyricsWord$LyricsWordNative'
    native_method(word, 'LYRICS_NATIVE_LINE_TEXT_METHOD', [], 'java.lang.String')
    native_method(word, 'LYRICS_NATIVE_WORD_ID_METHOD', [], 'int')
    native_method(word, 'LYRICS_NATIVE_WHITESPACE_METHOD', [], 'boolean')
    agent = model + 'LyricsAgent$LyricsAgentNative'
    for suffix, result in [('NAME_TYPES', '[I'), ('TYPE', 'long'), ('ID', 'java.lang.String')]:
        native_method(agent, 'LYRICS_NATIVE_AGENT_' + suffix + '_METHOD', [], result)
    native_method(string_vector, 'LYRICS_NATIVE_VECTOR_SIZE_METHOD', [], 'long')
    native_method(string_vector, 'LYRICS_NATIVE_VECTOR_GET_METHOD', ['long'], 'java.lang.String')
    native_method(view_model, 'LYRICS_VIEW_MODEL_CURRENT_LANGUAGE_METHOD', [], 'java.lang.String')
    native_method(view_model, 'LYRICS_VIEW_MODEL_RESULT_GETTER', [], 'androidx.lifecycle.G')

    catalog = names('MEDIA_API_REPOSITORY_HOLDER_CLASS')
    entity = 'com.apple.android.music.mediaapi.models.MediaEntity'
    attributes = 'com.apple.android.music.mediaapi.models.internals.Attributes'
    params = 'com.apple.android.music.mediaapi.models.internals.PlayParams'
    response = 'com.apple.android.music.mediaapi.repository.MediaApiResponse'
    for suffix, result in [('DATA', '[L' + entity + ';'), ('STATUS', 'java.lang.Integer'),
                           ('ERRORS', '[Lcom.apple.android.music.mediaapi.models.Error;')]:
        method(response, catalog['CATALOG_RESPONSE_' + suffix + '_METHOD'], [], result, False)
    for suffix, result in [('ID', 'java.lang.String'), ('ATTRIBUTES', attributes), ('RELATIONSHIPS', 'java.util.Map')]:
        method(entity, catalog['CATALOG_ENTITY_' + suffix + '_METHOD'], [], result, False)
    method(attributes, catalog['CATALOG_ATTRIBUTES_PLAY_PARAMS_METHOD'], [], params, False)
    method(params, catalog['CATALOG_PLAY_PARAMS_CATALOG_ID_METHOD'], [], 'java.lang.String', False)
    for suffix in ['NAME', 'ARTIST_NAME', 'ALBUM_NAME', 'ISRC', 'GENRE_NAME', 'GENRE_NAMES']:
        result = '[Ljava.lang.String;' if suffix == 'GENRE_NAMES' else 'java.lang.String'
        method(attributes, catalog['CATALOG_ATTRIBUTES_' + suffix + '_METHOD'], [], result, False)
    for suffix in ['NAME', 'ARTIST_NAME', 'ALBUM_NAME']:
        method(attributes, catalog['CATALOG_ATTRIBUTES_SET_' + suffix + '_METHOD'], ['java.lang.String'], 'void', False)
    method('com.apple.android.music.mediaapi.models.internals.Relationship',
           catalog['CATALOG_RELATIONSHIP_ENTITIES_METHOD'], [], '[L' + entity + ';', False)
