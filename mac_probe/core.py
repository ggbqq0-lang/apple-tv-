"""Evidence-aware keyboard operations. Never log text, credentials, or raw errors."""

import asyncio
from dataclasses import asdict, dataclass
from typing import Optional

from pyatv.const import KeyboardFocusState


class ProbeError(Exception):
    """A safe, user-facing error code, never a protocol payload."""


@dataclass(frozen=True)
class SendEvidence:
    status: str
    mode: str
    characters: int
    utf8_bytes: int
    focus_before: str
    readback_matches: Optional[bool] = None
    visual_confirmation_required: bool = True
    automatic_retry: bool = False

    def safe_dict(self):
        return asdict(self)


class TextSender:
    """Serialize writes, check fresh focus, and keep ambiguous writes non-retryable.

    pyatv text_set/append emit _tiC events without a per-write acknowledgement.
    Even a matching text_get is protocol context evidence, not visual acceptance.
    text_get reads contextBeforeInput and may omit text after the cursor.
    """

    def __init__(self, keyboard, timeout=8.0):
        self.keyboard = keyboard
        self.timeout = timeout
        self._busy = False
        self.needs_review = False

    def acknowledge_review(self):
        self.needs_review = False

    async def focus(self):
        try:
            current = await asyncio.wait_for(self.keyboard.text_get(), self.timeout)
        except (Exception, asyncio.CancelledError) as error:
            if isinstance(error, asyncio.CancelledError):
                raise
            raise ProbeError("focus_query_failed") from None
        return self.keyboard.text_focus_state.name, current is not None

    async def send(self, text: str, mode="replace", confirm_replace=False):
        if mode not in ("replace", "append", "clear"):
            raise ProbeError("invalid_mode")
        if mode == "clear" and text:
            raise ProbeError("clear_must_be_empty")
        # Encoding validation does not normalize or transform Unicode.
        try:
            encoded = text.encode("utf-8", errors="strict")
        except UnicodeError:
            raise ProbeError("invalid_unicode") from None
        if len(encoded) > 64 * 1024:
            raise ProbeError("local_safety_limit_64k_not_protocol_limit")
        if mode in ("replace", "clear") and not confirm_replace:
            raise ProbeError("replacement_confirmation_required")
        if self._busy:
            raise ProbeError("send_in_progress")
        if self.needs_review:
            raise ProbeError("previous_write_needs_visual_review")
        self._busy = True
        mutation_started = False
        try:
            before = await asyncio.wait_for(self.keyboard.text_get(), self.timeout)
            focus = self.keyboard.text_focus_state
            if before is None or focus != KeyboardFocusState.Focused:
                raise ProbeError("no_focused_text_field")
            expected = before + text if mode == "append" else text
            mutation_started = True
            if mode == "replace":
                await asyncio.wait_for(self.keyboard.text_set(text), self.timeout)
            elif mode == "append":
                await asyncio.wait_for(self.keyboard.text_append(text), self.timeout)
            else:
                await asyncio.wait_for(self.keyboard.text_clear(), self.timeout)
            # RTI events have no ack. Allow a bounded interval before querying.
            await asyncio.sleep(0.25)
            try:
                after = await asyncio.wait_for(self.keyboard.text_get(), self.timeout)
            except Exception:
                after = None
            match = None if after is None else after == expected
            status = (
                "protocol_context_matches" if match is True
                else "protocol_context_differs" if match is False
                else "event_dispatched_unconfirmed"
            )
            # Every write requires observation before another write; in particular,
            # do not turn a timeout, double click or lost ack into duplicate append.
            self.needs_review = True
            return SendEvidence(status, mode, len(text), len(encoded), focus.name, match)
        except ProbeError:
            raise
        except asyncio.CancelledError:
            if mutation_started:
                self.needs_review = True
            raise
        except Exception:
            if mutation_started:
                self.needs_review = True
                raise ProbeError("write_outcome_unknown_do_not_retry") from None
            raise ProbeError("connection_or_focus_query_failed_no_write") from None
        finally:
            self._busy = False


def synthetic_cases():
    """Public, non-secret inputs; returned values must not be used as real tokens."""
    result = {
        "hello": "hello123",
        "https": "https://example.com",
        "emby": "https://emby.example.com:8096",
        "query": "https://example.com/api?token=abc123&mode=1",
        "account": "Test_ABC-123@example.com",
        "spaces": "  includes spaces  ",
        "unicode": "包含中文的字符串 Café e\u0301 😀𠮷",
        "symbols": "!@#$%^&*()_+-=[]{};:'\"\\|,<.>/?`~%2F&x=1",
        "password-demo": "Abc@123456!",
    }
    for length in (20, 100, 300, 500, 1000):
        seed = "https://example.com/?x=AbC_%2F-123&mode=1"
        result[f"length-{length}"] = (seed * (length // len(seed) + 1))[:length]
    return result
