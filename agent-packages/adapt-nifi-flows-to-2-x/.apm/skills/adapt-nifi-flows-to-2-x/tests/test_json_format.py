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
