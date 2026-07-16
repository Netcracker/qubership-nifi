import pytest

from json_format import (
    DEFAULT_INDENT,
    WARN_COLON,
    WARN_EMPTY,
    WARN_FIXED_SPACE,
    JsonFormat,
    detect_format,
    dumps,
    reformat,
)


# ---------------------------------------------------------------------------
# detect_format - reproducible dimensions
# ---------------------------------------------------------------------------

def test_defaults_are_four_space_with_trailing_newline():
    fmt = JsonFormat.defaults()
    assert fmt.indent == "    "
    assert fmt.key_separator == ": "
    assert fmt.item_separator == ","
    assert fmt.trailing_newline is True
    assert fmt.warnings == ()


def test_detect_two_space_expanded():
    fmt = detect_format('{\n  "a": 1,\n  "b": 2\n}')
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert fmt.item_separator == ","
    assert fmt.trailing_newline is False
    assert fmt.warnings == ()


def test_detect_four_space_expanded():
    assert detect_format('{\n    "a": 1\n}').indent == "    "


def test_detect_zero_space_expanded():
    assert detect_format('{\n"a": 1,\n"b": 2\n}').indent == ""


def test_detect_tab_indent():
    assert detect_format('{\n\t"a": 1\n}').indent == "\t"


def test_detect_nested_indent_unit():
    fmt = detect_format('{\n  "a": {\n    "b": 1\n  }\n}')
    assert fmt.indent == "  "


def test_detect_compact_inline():
    fmt = detect_format('{"a":1,"b":[1,2]}')
    assert fmt.indent is None
    assert fmt.key_separator == ":"
    assert fmt.item_separator == ","
    assert fmt.warnings == ()


def test_detect_inline_space_after_comma():
    fmt = detect_format('{"a": 1, "b": 2}')
    assert fmt.indent is None
    assert fmt.key_separator == ": "
    assert fmt.item_separator == ", "


def test_detect_compact_empty_containers_do_not_warn():
    fmt = detect_format('{"a":{},"b":[]}')
    assert fmt.warnings == ()


def test_empty_containers_cast_no_style_vote():
    # The only populated container is expanded; "b" and "c" must not drag the
    # document towards the inline layout.
    fmt = detect_format('{\n  "a": [\n    1\n  ],\n  "b": {},\n  "c": []\n}')
    assert fmt.indent == "  "


# ---------------------------------------------------------------------------
# detect_format - arrays of objects
#
# Every array that carries weight in a flow export holds objects rather than
# scalars: processors, connections, controllerServices, processGroups. Such an
# array votes on its own style and on the style of each member object, so it can
# carry two container layouts at once - something a scalar array cannot do.
# ---------------------------------------------------------------------------

def test_detect_expanded_array_of_objects():
    fmt = detect_format('{\n  "a": [\n    {\n      "b": 1\n    }\n  ]\n}')
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert fmt.item_separator == ","
    assert fmt.warnings == ()


def test_detect_indent_unit_through_object_array_nesting():
    # Each object member sinks its contents two levels deeper, so the leading
    # whitespace is only a whole multiple of the indent unit if the depth is
    # counted right.
    content = (
        '{\n'
        '    "processGroups": [\n'
        '        {\n'
        '            "processors": [\n'
        '                {\n'
        '                    "properties": {\n'
        '                        "x": "1"\n'
        '                    }\n'
        '                }\n'
        '            ]\n'
        '        }\n'
        '    ]\n'
        '}'
    )
    assert detect_format(content).indent == "    "


def test_detect_compact_array_of_objects():
    # The comma between "}" and "{" belongs to the array, and only a
    # non-expanded container tallies comma spacing at all.
    fmt = detect_format('{"a":[{"b":1},{"c":2}]}')
    assert fmt.indent is None
    assert fmt.key_separator == ":"
    assert fmt.item_separator == ","
    assert fmt.warnings == ()


def test_detect_inline_array_of_objects_with_spaced_commas():
    fmt = detect_format('{"a": [{"b": 1}, {"c": 2}]}')
    assert fmt.indent is None
    assert fmt.key_separator == ": "
    assert fmt.item_separator == ", "


def test_empty_objects_in_array_cast_no_style_vote():
    # "{}" members reveal nothing about how a populated object is laid out, so
    # the expanded array must survive them.
    fmt = detect_format('{\n  "a": [\n    {},\n    {}\n  ]\n}')
    assert fmt.indent == "  "
    assert fmt.warnings == ()


def test_detect_trailing_newline():
    assert detect_format('{"a":1}\n').trailing_newline is True
    assert detect_format('{"a":1}').trailing_newline is False


def test_empty_document_falls_back_to_defaults():
    assert detect_format("{}\n") == JsonFormat.defaults()


def test_picks_dominant_indent_when_mixed():
    # Three lines indented by 2, one by 4.
    content = '{\n  "a": 1,\n  "b": 2,\n  "c": {\n      "d": 3\n  }\n}'
    assert detect_format(content).indent == "  "


def test_crlf_detects_the_same_indent_as_lf():
    # Line endings are out of scope, but a stray '\r' must not be counted as
    # leading whitespace.
    lf = '{\n  "a": 1,\n  "b": 2\n}'
    crlf = lf.replace("\n", "\r\n")
    assert detect_format(crlf).indent == detect_format(lf).indent == "  "


def test_structural_chars_inside_strings_do_not_vote():
    fmt = detect_format('{\n  "a": "{not:a,brace}",\n  "b": "[1,2]"\n}')
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert fmt.warnings == ()


def test_escaped_quote_inside_string_does_not_end_the_string():
    fmt = detect_format('{\n  "a": "he said \\"hi\\" : x"\n}')
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert fmt.warnings == ()


def test_quoted_fragments_in_property_value_do_not_vote():
    # A processor property quoting the payloads it rejected. Each fragment sits
    # between a pair of escaped quotes, laid out in a style the document does
    # not use. Unless the escapes are honored the two of them outvote the one
    # real array, "processors", and carry the document to the fixed-space style.
    content = (
        '{\n'
        '  "processors": [\n'
        '    {\n'
        '      "properties": {\n'
        '        "Message": "rejected \\"[ 1, 2 ]\\" and \\"[ 3, 4 ]\\" as invalid"\n'
        '      }\n'
        '    }\n'
        '  ]\n'
        '}'
    )
    fmt = detect_format(content)
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert fmt.warnings == ()


# ---------------------------------------------------------------------------
# detect_format - warn and approximate
# ---------------------------------------------------------------------------

def test_spaced_colon_warns_and_approximates():
    fmt = detect_format('{"a" : 1}')
    assert fmt.key_separator == ": "
    assert fmt.warnings == (WARN_COLON,)


def test_spaced_empty_containers_warn_and_approximate():
    fmt = detect_format('{"a":{ },"b":[ ]}')
    assert fmt.warnings == (WARN_EMPTY,)
    assert dumps({"a": {}, "b": []}, fmt) == '{"a":{},"b":[]}'


def test_jackson_default_warns_twice_and_stays_expanded():
    fmt = detect_format('{\n  "a" : [ 1, 2 ],\n  "b" : [ "x" ]\n}')
    # Expanded objects plus fixed-space arrays: the closest reproducible form is
    # a fully expanded document, not a collapsed one.
    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert set(fmt.warnings) == {WARN_FIXED_SPACE, WARN_COLON}
    assert dumps({"a": [1, 2]}, fmt) == '{\n  "a": [\n    1,\n    2\n  ]\n}'


def test_fixed_space_only_warns_and_preserves_comma_spacing():
    content = '{ "a" : [ 1, 2 ], "b" : { "c" : 3 } }'
    fmt = detect_format(content)

    assert fmt.indent is None
    assert fmt.item_separator == ", "
    assert set(fmt.warnings) == {WARN_FIXED_SPACE, WARN_COLON}
    assert dumps({"a": [1, 2], "b": {"c": 3}}, fmt) == (
        '{"a": [1, 2], "b": {"c": 3}}'
    )


def test_jackson_object_array_warns_twice_and_stays_expanded():
    # The shape Jackson gives a NiFi 1.x export, and so the most important input
    # this module has: fixed-space arrays holding expanded objects.
    content = '{\n  "a" : [ {\n    "b" : 1\n  }, {\n    "b" : 2\n  } ]\n}'
    fmt = detect_format(content)

    assert fmt.indent == "  "
    assert fmt.key_separator == ": "
    assert set(fmt.warnings) == {WARN_FIXED_SPACE, WARN_COLON}
    # An expanded vote is on the table, so the document expands rather than
    # collapses.
    assert dumps({"a": [{"b": 1}, {"b": 2}]}, fmt) == (
        '{\n  "a": [\n    {\n      "b": 1\n    },\n    {\n      "b": 2\n    }\n  ]\n}'
    )


def test_fixed_space_object_array_collapses_when_nothing_is_expanded():
    # Two members, so that the array actually holds a comma to sample.
    content = '{ "a" : [ { "b" : 1 }, { "b" : 2 } ] }'
    fmt = detect_format(content)

    assert fmt.indent is None
    assert fmt.item_separator == ", "
    assert set(fmt.warnings) == {WARN_FIXED_SPACE, WARN_COLON}
    assert dumps({"a": [{"b": 1}, {"b": 2}]}, fmt) == '{"a": [{"b": 1}, {"b": 2}]}'


# ---------------------------------------------------------------------------
# dumps
# ---------------------------------------------------------------------------

def test_dumps_appends_trailing_newline_only_when_detected():
    data = {"a": 1}
    assert dumps(data, JsonFormat(indent=None, key_separator=":")) == '{"a":1}\n'
    assert (
        dumps(data, JsonFormat(indent=None, key_separator=":", trailing_newline=False))
        == '{"a":1}'
    )


# Built with chr() so that this source file stays ASCII-only, as the repository
# requires, while the values under test do not.
_E_ACUTE = chr(0xE9)
_A_GRAVE = chr(0xE0)


def test_dumps_preserves_non_ascii():
    fmt = JsonFormat(indent=None, key_separator=":")
    assert dumps({"a": _E_ACUTE}, fmt) == '{"a":"' + _E_ACUTE + '"}\n'


def test_dumps_preserves_key_order():
    fmt = JsonFormat(indent=None, key_separator=":", trailing_newline=False)
    assert dumps({"b": 1, "a": 2}, fmt) == '{"b":1,"a":2}'


def test_default_indent_matches_the_default_format():
    assert JsonFormat.defaults().indent == DEFAULT_INDENT


# ---------------------------------------------------------------------------
# reformat - round-trip identity
# ---------------------------------------------------------------------------

_STABLE_SAMPLES = [
    ("zero space expanded", '{\n"a": 1,\n"b": 2\n}'),
    ("two space expanded", '{\n  "a": 1,\n  "b": 2\n}'),
    ("four space expanded", '{\n    "a": 1,\n    "b": 2\n}'),
    ("tab expanded", '{\n\t"a": 1,\n\t"b": 2\n}'),
    ("nested expanded", '{\n  "a": {\n    "b": [\n      1,\n      2\n    ]\n  }\n}'),
    ("compact inline", '{"a":1,"b":[1,2]}'),
    ("inline spaced colon and comma", '{"a": 1, "b": 2}'),
    ("compact empty containers", '{"a":{},"b":[]}'),
    ("trailing newline", '{\n  "a": 1\n}\n'),
    ("structural chars inside strings", '{\n  "a": "{not:a,brace}",\n  "b": "[1,2]"\n}'),
    ("non ascii value", '{\n  "a": "' + _E_ACUTE + _A_GRAVE + '"\n}'),
    (
        "expanded array of objects",
        '{\n  "a": [\n    {\n      "b": 1\n    },\n    {\n      "b": 2\n    }\n  ]\n}',
    ),
    (
        "four space array of objects",
        '{\n    "a": [\n        {\n            "b": 1\n        }\n    ]\n}',
    ),
    ("tab array of objects", '{\n\t"a": [\n\t\t{\n\t\t\t"b": 1\n\t\t}\n\t]\n}'),
    ("compact array of objects", '{"a":[{"b":1},{"c":2}]}'),
    ("inline array of objects", '{"a": [{"b": 1}, {"c": 2}]}'),
    (
        "nested object arrays",
        '{\n'
        '  "processGroups": [\n'
        '    {\n'
        '      "processors": [\n'
        '        {\n'
        '          "properties": {\n'
        '            "x": "1"\n'
        '          }\n'
        '        }\n'
        '      ]\n'
        '    }\n'
        '  ]\n'
        '}',
    ),
    (
        "array of arrays of objects",
        '{\n  "a": [\n    [\n      {\n        "b": 1\n      }\n    ]\n  ]\n}',
    ),
    ("empty object array", '{\n  "processors": []\n}'),
]


@pytest.mark.parametrize(
    "content", [s[1] for s in _STABLE_SAMPLES], ids=[s[0] for s in _STABLE_SAMPLES]
)
def test_reformat_round_trips(content):
    assert reformat(content) == content


@pytest.mark.parametrize(
    "content,expected",
    [
        ('{"a" : 1,"b" : 2}', '{"a": 1,"b": 2}'),
        ('{"a":{ },"b":[ ]}', '{"a":{},"b":[]}'),
    ],
)
def test_reformat_approximates_unreproducible_layouts(content, expected):
    assert reformat(content) == expected


def test_inline_objects_in_expanded_array_are_normalized():
    # One style wins per dimension, so an array that keeps its members on a
    # single line each is rewritten onto the dominant expanded layout. This one
    # does not round-trip, by design.
    content = '{\n  "a": [\n    {"b": 1},\n    {"b": 2}\n  ]\n}'
    assert reformat(content) == (
        '{\n  "a": [\n    {\n      "b": 1\n    },\n    {\n      "b": 2\n    }\n  ]\n}'
    )
