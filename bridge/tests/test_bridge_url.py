"""
Tests for the bridge-URL validation rules.

The bridge address is set from a text field in the app, and the value is
persisted on every keystroke. If the validation is too loose, a half-typed
address gets stored and the app silently talks to nothing. If it is too
strict, a legitimate address is rejected and the app cannot talk to the
bridge at all.

The rules are mirrored from `BridgeConfig.kt` in the Android app. Keep the two
in step.
"""

import re

VALID = re.compile(
    r"^(?P<scheme>https?)://"
    r"(?P<host>[^/:?#\s]+|\[[0-9A-Fa-f:]+\])"
    r"(?::(?P<port>\d+))?"
    r"(?P<path>/[^?#\s]*)?"
    r"(?:\?(?P<query>[^#\s]*))?"
    r"(?:#(?P<fragment>\S*))?$"
)


def validate(raw: str) -> str | None:
    """Mirror of BridgeConfig.setUrl. Returns the cleaned URL or None."""
    cleaned = raw.strip().rstrip("/")
    if not cleaned:
        return None
    if not cleaned.startswith(("http://", "https://")):
        return None

    # The Kotlin code takes the authority as everything after "://" and before
    # the first "/".
    authority = cleaned.split("://", 1)[1].split("/", 1)[0]
    if not authority or " " in authority:
        return None

    host = authority.split(":")[0]
    if not host or ("." not in host and host != "localhost"):
        return None

    # A dotted-numeric host is almost always an IP being typed. "192.168" and
    # "192.168.1" are partial addresses, so reject them rather than pointing
    # the app at a host that does not exist.
    if re.fullmatch(r"[0-9.]+", host):
        octets = host.split(".")
        if len(octets) != 4 or not all(o.isdigit() and int(o) <= 255 for o in octets):
            return None

    return cleaned


# --------------------------------------------------------------------------- tests


def test_plain_lan_address_is_accepted():
    assert validate("http://192.168.1.3:8790") == "http://192.168.1.3:8790"


def test_hostname_is_accepted():
    assert validate("http://bridge.local:8790") == "http://bridge.local:8790"


def test_localhost_is_accepted():
    assert validate("http://localhost:8790") == "http://localhost:8790"


def test_https_is_accepted():
    assert validate("https://bridge.example.com") == "https://bridge.example.com"


def test_trailing_slash_is_stripped():
    assert validate("http://192.168.1.3:8790/") == "http://192.168.1.3:8790"


def test_whitespace_is_trimmed():
    assert validate("  http://192.168.1.3:8790  ") == "http://192.168.1.3:8790"


def test_empty_is_rejected():
    assert validate("") is None
    assert validate("   ") is None


def test_missing_scheme_is_rejected():
    assert validate("192.168.1.3:8790") is None
    assert validate("//192.168.1.3:8790") is None


def test_wrong_scheme_is_rejected():
    assert validate("ftp://192.168.1.3") is None
    assert validate("file:///etc/passwd") is None


def test_partial_typing_is_rejected():
    """The important case: this runs on every keystroke."""
    assert validate("h") is None
    assert validate("ht") is None
    assert validate("http") is None
    assert validate("http:") is None
    assert validate("http://") is None
    assert validate("http://1") is None
    assert validate("http://192") is None
    assert validate("http://192.168") is None
    assert validate("http://192.168.1") is None
    assert validate("http://192.168.1.") is None


def test_host_only_address_is_accepted():
    """A complete host with no port is still a complete address."""
    assert validate("http://192.168.1.3") == "http://192.168.1.3"


def test_address_with_a_space_is_rejected():
    assert validate("http://192.168.1.3 :8790") is None


def test_tailscale_address_is_accepted():
    assert validate("http://100.103.241.112:8790") == "http://100.103.241.112:8790"


def test_rewrite_of_a_stored_url_keeps_working():
    """Re-entering the same address must be idempotent."""
    once = validate("http://192.168.1.3:8790/")
    assert once is not None
    twice = validate(once)
    assert once == twice
