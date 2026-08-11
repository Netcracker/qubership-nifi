"""
json_format.py  -  Detect and reproduce the textual formatting of a JSON document.

Ported from the Java library in qubership-nifi-tools/qubership-nifi-tools-common,
package org.qubership.nifi.tools.jsonformat. Rewriting a flow export through
detect_format() and dumps() preserves the original layout, so the resulting diff
holds only the edits that were actually made.

The document is scanned once, left to right. The scan is string-aware: it tracks
whether it is inside a string literal, honoring escape sequences, so that
structural characters such as ':', ',', '{' or '[' appearing inside string values
do not affect detection. For each formatting dimension the most frequent
observation wins ("dominant-wins"); a dimension that never occurs falls back to
the default.

Limitations:
  - Only the dimensions json.dump can reproduce are detected: the indent unit,
    colon and comma spacing, and the trailing newline.
  - Line endings are neither detected nor reproduced. Git normalizes them, so
    output is always written with LF and the caller's text-mode open() decides
    what reaches the disk.
  - Three Jackson layouts are detected but cannot be reproduced: a space before
    the colon, fixed-space containers, and spaced empty containers. Each adds a
    warning to the returned format and is written in the closest reproducible
    form.
  - One global style wins per dimension, so a document that mixes layouts from
    one node to the next is normalized onto the dominant one.
  - Scalars are rendered by Python, so a float is written as repr() gives it.
  - Key order is preserved.
"""

import json
from dataclasses import dataclass, field


# ---------------------------------------------------------------------------
# Format description
# ---------------------------------------------------------------------------

# Deliberately 4 spaces, unlike the Java JsonFormat.DEFAULT_INDENT of 2
DEFAULT_INDENT = "    "

# Container layout, as observed between an opening bracket and its first member.
_EXPANDED = "expanded"          # a newline follows the bracket
_FIXED_SPACE = "fixed_space"    # spaces follow the bracket, as in [ 1, 2 ]
_INLINE = "inline"              # the member follows the bracket immediately

# Whitespace observed around a separator.
_NONE = "none"
_BEFORE = "before"
_AFTER = "after"
_BOTH = "both"

WARN_FIXED_SPACE = (
    'fixed-space containers ("[ 1, 2 ]") cannot be reproduced - '
    "writing the closest reproducible form"
)
WARN_COLON = 'a space before ":" ("a" : 1) cannot be reproduced - writing "a": 1'
WARN_EMPTY = 'spaced empty containers ("{ }") cannot be reproduced - writing "{}"'


@dataclass(frozen=True)
class JsonFormat:
    """
    The textual formatting of a JSON document, as far as json.dump can reproduce it.

    warnings lists the layouts that were detected but cannot be reproduced. It is
    excluded from equality: it describes a format rather than identifying one, so
    two documents laid out the same way compare equal even if only one warns.
    """

    indent: str | None = DEFAULT_INDENT     # None means compact, no newlines
    key_separator: str = ": "               # ": " or ":"
    item_separator: str = ","               # "," or ", " (", " only when compact)
    trailing_newline: bool = True
    warnings: tuple[str, ...] = field(default=(), compare=False, repr=False)

    @classmethod
    def defaults(cls) -> "JsonFormat":
        return cls()


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------


class _Tallies:
    """Frequency counters collected during a single detection pass."""

    def __init__(self) -> None:
        self.indent_width: dict[int, int] = {}
        self.indent_char: dict[str, int] = {}
        self.object_style: dict[str, int] = {}
        self.array_style: dict[str, int] = {}
        self.colon: dict[str, int] = {}
        self.comma: dict[str, int] = {}
        self.object_empty: dict[str, int] = {}
        self.array_empty: dict[str, int] = {}


def _inc(counts: dict, key) -> None:
    counts[key] = counts.get(key, 0) + 1


def _dominant(counts: dict):
    """Return the most frequent key, or None when nothing was observed."""
    best = None
    best_count = 0
    for key, count in counts.items():
        if count > best_count:
            best_count = count
            best = key
    return best


def _sample_indent(content: str, line_start: int, depth: int, tallies: _Tallies) -> None:
    """Infer the per-level indent unit from one line's leading whitespace."""
    n = len(content)
    j = line_start
    length = 0
    first = " "
    while j < n and content[j] in " \t":
        if length == 0:
            first = content[j]
        length += 1
        j += 1
    if j >= n:
        return
    c = content[j]
    if c in "\n\r":
        return          # blank line, no opinion
    if c in "}]":
        return          # a closing bracket sits one level out
    if depth >= 1 and length % depth == 0:
        _inc(tallies.indent_width, length // depth)
        if length > 0:
            _inc(tallies.indent_char, first)


def _colon_spacing(content: str, index: int) -> str:
    before = 0
    p = index - 1
    while p >= 0 and content[p] in " \t":
        before += 1
        p -= 1
    after = 0
    q = index + 1
    n = len(content)
    while q < n and content[q] in " \t":
        after += 1
        q += 1
    if before > 0 and after > 0:
        return _BOTH
    if before > 0:
        return _BEFORE
    if after > 0:
        return _AFTER
    return _NONE


def _handle_container_start(content: str, index: int, opener: str, tallies: _Tallies) -> str:
    is_array = opener == "["
    closer = "]" if is_array else "}"
    n = len(content)
    k = index + 1
    while k < n and content[k] in " \t":
        k += 1
    if k < n and content[k] == closer:
        # An empty container reveals its separator but casts no style vote:
        # "properties": {} says nothing about how populated objects are laid out.
        _inc(
            tallies.array_empty if is_array else tallies.object_empty,
            " " if k > index + 1 else "",
        )
        return _INLINE
    if k < n and content[k] in "\n\r":
        style = _EXPANDED
    elif k > index + 1:
        style = _FIXED_SPACE
    else:
        style = _INLINE
    _inc(tallies.array_style if is_array else tallies.object_style, style)
    return style


def _handle_comma(content: str, index: int, frame, tallies: _Tallies) -> None:
    """Tally reproducible comma spacing inside non-expanded containers."""
    if frame is None or frame[1] == _EXPANDED:
        return
    n = len(content)
    q = index + 1
    after = 0
    while q < n and content[q] in " \t":
        after += 1
        q += 1
    _inc(tallies.comma, _AFTER if after > 0 else _NONE)


def detect_format(content: str) -> JsonFormat:
    """Detect the formatting of the given JSON text."""
    tallies = _Tallies()
    frames: list[tuple[bool, str]] = []     # (is_array, style), innermost last
    depth = 0
    in_string = False
    n = len(content)
    i = 0
    while i < n:
        c = content[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == '"':
            in_string = True
        elif c == "\n":
            _sample_indent(content, i + 1, depth, tallies)
        elif c in "{[":
            frames.append((c == "[", _handle_container_start(content, i, c, tallies)))
            depth += 1
        elif c in "}]":
            depth -= 1
            if frames:
                frames.pop()
        elif c == ":":
            _inc(tallies.colon, _colon_spacing(content, i))
        elif c == ",":
            _handle_comma(content, i, frames[-1] if frames else None, tallies)
        i += 1
    return _build(tallies, content)


def _build(tallies: _Tallies, content: str) -> JsonFormat:
    """Reduce the tallies onto what json.dump can express, warning about the rest."""
    warnings: list[str] = []

    observed = [
        style
        for style in (_dominant(tallies.object_style), _dominant(tallies.array_style))
        if style is not None
    ]
    if _FIXED_SPACE in observed:
        warnings.append(WARN_FIXED_SPACE)

    # With no style observed at all - an empty or scalar-only document - the
    # default expanded layout applies.
    if not observed or _EXPANDED in observed:
        indent_char = _dominant(tallies.indent_char) or " "
        indent_width = _dominant(tallies.indent_width)
        if indent_width is None:
            indent_width = len(DEFAULT_INDENT)
        indent = indent_char * indent_width
        # A space here would trail every line, just before its newline.
        item_separator = ","
    else:
        indent = None
        item_separator = ", " if _dominant(tallies.comma) == _AFTER else ","

    colon = _dominant(tallies.colon) or _AFTER
    if colon in (_BEFORE, _BOTH):
        warnings.append(WARN_COLON)
    key_separator = ": " if colon in (_AFTER, _BOTH) else ":"

    if " " in (_dominant(tallies.object_empty), _dominant(tallies.array_empty)):
        warnings.append(WARN_EMPTY)

    return JsonFormat(
        indent=indent,
        key_separator=key_separator,
        item_separator=item_separator,
        trailing_newline=content.endswith("\n"),
        warnings=tuple(warnings),
    )


# ---------------------------------------------------------------------------
# Serialization
# ---------------------------------------------------------------------------


def dumps(data, fmt: JsonFormat) -> str:
    """Serialize data in the given format."""
    body = json.dumps(
        data,
        indent=fmt.indent,
        separators=(fmt.item_separator, fmt.key_separator),
        ensure_ascii=False,
    )
    if fmt.trailing_newline:
        body += "\n"
    return body


def reformat(content: str) -> str:
    """Parse JSON text and write it back in its own detected format."""
    return dumps(json.loads(content), detect_format(content))
