"""Probe safety tests and upstream serialization reference checks, no real TV."""

import asyncio
import json
import plistlib
from unittest.mock import AsyncMock

import pytest
from cryptography.exceptions import InvalidTag
from pyatv.auth.hap_tlv8 import read_tlv, write_tlv
from pyatv.const import KeyboardFocusState as Focus
from pyatv.protocols.companion.connection import CompanionConnection, FrameType
from pyatv.protocols.companion.keyed_archiver import read_archive_properties
from pyatv.protocols.companion.plist_payloads import (
    get_rti_clear_text_payload, get_rti_input_text_payload,
)
from pyatv.support import opack
from pyatv.support.chacha20 import Chacha20Cipher

from mac_probe.core import ProbeError, TextSender, synthetic_cases


class Keyboard:
    def __init__(self, text="", focus=Focus.Focused):
        self.text = text
        self.text_focus_state = focus
        self.writes = []

    async def text_get(self):
        return self.text if self.text_focus_state == Focus.Focused else None

    async def text_set(self, text):
        self.writes.append(("replace", text))
        self.text = text

    async def text_append(self, text):
        self.writes.append(("append", text))
        self.text += text

    async def text_clear(self):
        self.writes.append(("clear", ""))
        self.text = ""


def run(awaitable):
    return asyncio.run(awaitable)


@pytest.mark.parametrize("text", synthetic_cases().values(), ids=synthetic_cases().keys())
def test_exact_text_replacement(text):
    keyboard = Keyboard("old")
    evidence = run(TextSender(keyboard).send(text, confirm_replace=True))
    assert keyboard.text == text
    assert keyboard.writes == [("replace", text)]
    assert evidence.status == "protocol_context_matches"
    assert evidence.visual_confirmation_required
    assert evidence.automatic_retry is False
    assert evidence.utf8_bytes == len(text.encode())


def test_append_clear_and_review_gate():
    async def scenario():
        keyboard = Keyboard("prefix:")
        sender = TextSender(keyboard)
        await sender.send(" 中文&%2F", "append")
        assert keyboard.text == "prefix: 中文&%2F"
        with pytest.raises(ProbeError, match="previous_write_needs_visual_review"):
            await sender.send("duplicate", "append")
        sender.acknowledge_review()
        await sender.send("", "clear", confirm_replace=True)
        assert keyboard.text == ""
    run(scenario())


@pytest.mark.parametrize("focus", [Focus.Unknown, Focus.Unfocused])
def test_no_focus_means_no_write(focus):
    keyboard = Keyboard(focus=focus)
    with pytest.raises(ProbeError, match="no_focused_text_field"):
        run(TextSender(keyboard).send("hello", confirm_replace=True))
    assert not keyboard.writes


def test_stale_focus_does_not_authorize_write():
    keyboard = Keyboard()
    keyboard.text_get = AsyncMock(return_value=None)
    with pytest.raises(ProbeError, match="no_focused_text_field"):
        run(TextSender(keyboard).send("hello", confirm_replace=True))
    assert not keyboard.writes


@pytest.mark.parametrize("mode", ["replace", "clear"])
def test_confirmation_required(mode):
    keyboard = Keyboard()
    with pytest.raises(ProbeError, match="replacement_confirmation_required"):
        run(TextSender(keyboard).send("", mode))
    assert not keyboard.writes


def test_ambiguous_write_is_not_retried_or_logged():
    async def scenario():
        keyboard = Keyboard()
        keyboard.text_append = AsyncMock(side_effect=ConnectionError("SECRET_TOKEN"))
        sender = TextSender(keyboard)
        with pytest.raises(ProbeError) as error:
            await sender.send("SECRET_TOKEN", "append")
        assert str(error.value) == "write_outcome_unknown_do_not_retry"
        with pytest.raises(ProbeError, match="previous_write_needs_visual_review"):
            await sender.send("SECRET_TOKEN", "append")
        assert keyboard.text_append.call_count == 1
    run(scenario())


def test_timeout_before_write():
    async def scenario():
        keyboard = Keyboard()
        async def slow():
            await asyncio.sleep(1)
        keyboard.text_get = slow
        sender = TextSender(keyboard, timeout=0.001)
        with pytest.raises(ProbeError, match="connection_or_focus_query_failed_no_write"):
            await sender.send("hello", confirm_replace=True)
        assert not keyboard.writes
        assert not sender.needs_review
    run(scenario())


def test_concurrent_click_rejected():
    async def scenario():
        keyboard = Keyboard()
        sender = TextSender(keyboard)
        first = asyncio.create_task(sender.send("one", "append"))
        await asyncio.sleep(0.01)
        with pytest.raises(ProbeError, match="send_in_progress"):
            await sender.send("two", "append")
        await first
        assert len(keyboard.writes) == 1
    run(scenario())


@pytest.mark.parametrize("after,status", [("wrong", "protocol_context_differs"),
                                        (None, "event_dispatched_unconfirmed")])
def test_no_false_success(after, status):
    keyboard = Keyboard()
    keyboard.text_get = AsyncMock(side_effect=["", after])
    result = run(TextSender(keyboard).send("hello", confirm_replace=True))
    assert result.status == status
    assert "success" not in json.dumps(result.safe_dict())


def test_evidence_never_contains_payload():
    result = run(TextSender(Keyboard()).send("SECRET_SUBSCRIPTION_TOKEN", confirm_replace=True))
    assert "SECRET" not in json.dumps(result.safe_dict())
    assert "SECRET" not in repr(result)


def test_invalid_unicode_and_local_limit_are_explicit():
    sender = TextSender(Keyboard())
    with pytest.raises(ProbeError, match="invalid_unicode"):
        run(sender.send("\ud800", confirm_replace=True))
    with pytest.raises(ProbeError, match="local_safety_limit_64k_not_protocol_limit"):
        run(sender.send("x" * 65537, confirm_replace=True))


@pytest.mark.parametrize("name,text", synthetic_cases().items())
def test_upstream_rti_roundtrip_exact_unicode(name, text):
    session = bytes(range(16))
    archive = get_rti_input_text_payload(session, text)
    data = plistlib.loads(archive)
    assert data["$archiver"] == "RTIKeyedArchiver"
    assert read_archive_properties(archive,
        ["textOperations", "keyboardOutput", "insertionText"],
        ["textOperations", "targetSessionUUID", "NS.uuidbytes"]) == (text, session)
    message = {"_i": "_tiC", "_t": 1, "_x": 123, "_c": {"_tiV": 1, "_tiD": archive}}
    decoded, remaining = opack.unpack(opack.pack(message))
    assert decoded == message
    assert remaining == b""


def test_clear_payload_semantics():
    archive = get_rti_clear_text_payload(bytes(range(16)))
    assert read_archive_properties(archive, ["textOperations", "textToAssert"]) == ("",)


def test_tlv_fragmentation_3072bit_public_key():
    public_key = bytes(range(256)) + bytes(range(128))
    assert read_tlv(write_tlv({3: public_key, 6: b"\x03"})) == {3: public_key, 6: b"\x03"}


def test_opack_golden_header():
    assert opack.pack({"_i": "_tiStop", "_t": 2, "_c": {}}).hex() == "e3425f69475f746953746f70425f740a425f63e0"


def test_encrypted_reference_frames_and_counters():
    key1, key2 = bytes(range(32)), bytes(reversed(range(32)))
    client = Chacha20Cipher(key1, key2, nonce_length=12)
    server = Chacha20Cipher(key2, key1, nonce_length=12)
    payload = opack.pack({"_i": "_tiStart", "_t": 2})
    header = b"\x08" + (len(payload) + 16).to_bytes(3, "big")
    cipher1 = client.encrypt(payload, aad=header)
    assert server.decrypt(cipher1, aad=header) == payload
    cipher2 = client.encrypt(payload, aad=header)
    assert cipher1 != cipher2
    assert server.decrypt(cipher2, aad=header) == payload


def test_encryption_rejects_tampered_header():
    key = bytes(range(32))
    client = Chacha20Cipher(key, key, nonce_length=12)
    server = Chacha20Cipher(key, key, nonce_length=12)
    encrypted = client.encrypt(b"test", aad=b"\x08\x00\x00\x14")
    with pytest.raises(InvalidTag):
        server.decrypt(encrypted, aad=b"\x08\x00\x00\x15")


def test_upstream_frame_stream_fragmentation_and_coalescing():
    async def scenario():
        listener = type("Listener", (), {"frame_received": lambda self, t, d: self.frames.append((t, d))})()
        listener.frames = []
        connection = CompanionConnection(asyncio.get_running_loop(), "127.0.0.1", 1)
        connection.set_listener(listener)
        frame = b"\x08\x00\x00\x03abc"
        connection.data_received(frame[:2])
        assert not listener.frames
        connection.data_received(frame[2:5])
        assert not listener.frames
        connection.data_received(frame[5:] + frame)
        assert listener.frames == [(FrameType.E_OPACK, b"abc")] * 2
    run(scenario())
