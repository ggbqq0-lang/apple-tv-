"""Run with .venv/bin/python -m mac_probe.probe scan|session.

    Pairing credentials remain in MemoryStorage. No HTTP server, no clipboard
    monitoring, no text/credential logs, no automatic retransmission.
"""

import argparse
import asyncio
import getpass
import json
import logging
import sys

import pyatv
from pyatv.const import PairingRequirement, Protocol
from pyatv.auth.hap_tlv8 import ErrorCode, TlvValue, read_tlv
from pyatv.storage.memory_storage import MemoryStorage

from .core import ProbeError, TextSender, synthetic_cases

# Upstream debug/exception messages can contain credentials and text payloads.
logging.disable(logging.CRITICAL)


def emit(**values):
    print(json.dumps(values, ensure_ascii=False), flush=True)


async def discover(host=None, timeout=5):
    storage = MemoryStorage()
    configs = await asyncio.wait_for(
        pyatv.scan(asyncio.get_running_loop(), timeout=timeout,
                   storage=storage, hosts=[host] if host else None),
        timeout + 10,
    )
    # Companion is also advertised by Macs: do not treat every service as a TV.
    return [c for c in configs if c.get_service(Protocol.Companion) is not None
            and "AppleTV" in (c.device_info.raw_model or "")]


def device_summary(config):
    service = config.get_service(Protocol.Companion)
    return dict(name=config.name, address=str(config.address),
                model=config.device_info.model.name,
                raw_model=config.device_info.raw_model,
                advertised_tvos=config.device_info.version,
                companion_port=service.port, pairing=service.pairing.name)


async def session(args):
    loop = asyncio.get_running_loop()
    storage = MemoryStorage()
    configs = await discover(args.host)
    if len(configs) != 1:
        emit(status="select_exact_device", devices=[device_summary(c) for c in configs])
        return 2
    config = configs[0]
    service = config.get_service(Protocol.Companion)
    if service.pairing != PairingRequirement.Mandatory:
        emit(status="pairing_unavailable", device=device_summary(config))
        return 2
    emit(status="device_selected", device=device_summary(config))
    pairing = None
    atv = None
    try:
        pairing = await pyatv.pair(config, Protocol.Companion, loop, storage=storage,
                                   name="Apple TV 快捷输入助手·验证")
        # Observe only numeric state/error/backoff metadata, never crypto material.
        exchange_auth = pairing.protocol.exchange_auth
        async def observed_auth(frame_type, data, **kwargs):
            emit(status="auth_frame_sent", frame_type=frame_type.name)
            response = await exchange_auth(frame_type, data, **kwargs)
            tlv = read_tlv(response.get("_pd", b""))
            metadata = {}
            for key, label in ((TlvValue.SeqNo, "sequence"), (TlvValue.Error, "error_code"),
                               (TlvValue.BackOff, "backoff_seconds")):
                if key in tlv:
                    metadata[label] = int.from_bytes(tlv[key], "little")
            if "error_code" in metadata:
                try:
                    metadata["error_name"] = ErrorCode(metadata["error_code"]).name
                except ValueError:
                    metadata["error_name"] = "Unknown"
            emit(status="auth_frame_received", frame_type=frame_type.name, **metadata)
            return response
        pairing.protocol.exchange_auth = observed_auth
        await asyncio.wait_for(pairing.begin(), 15)
        emit(status="pin_required", note="请读取电视屏幕上的四位验证码；不会记录或保存验证码。")
        # getpass avoids terminal echo; a piped session reads from stdin.
        if sys.stdin.isatty():
            pin = await asyncio.wait_for(asyncio.to_thread(getpass.getpass, "TV PIN: "), 180)
        else:
            pin = (await asyncio.wait_for(asyncio.to_thread(sys.stdin.readline), 180)).strip()
        if len(pin) != 4 or not pin.isascii() or not pin.isdigit():
            raise ProbeError("four_digit_pin_required")
        pairing.pin(pin)
        pin = None
        await asyncio.wait_for(pairing.finish(), 15)
        if not pairing.has_paired:
            raise ProbeError("pairing_not_confirmed")
        emit(status="paired", storage="process_memory_only")
        await pairing.close()
        atv = await asyncio.wait_for(pyatv.connect(config, loop, protocol=Protocol.Companion,
                                                  storage=storage), 20)
        sender = TextSender(atv.keyboard)
        emit(status="connected", commands=["focus", "case", "send", "observed", "remote", "quit"])
        while True:
            line = await asyncio.to_thread(sys.stdin.readline)
            if not line:
                break
            try:
                request = json.loads(line)
                command = request.get("command")
                if command == "quit":
                    break
                if command == "focus":
                    focus, context = await sender.focus()
                    emit(status="focus_checked", focus=focus, context_available=context)
                elif command in ("case", "send"):
                    text = synthetic_cases()[request["name"]] if command == "case" else request["text"]
                    if not isinstance(text, str):
                        raise ProbeError("text_must_be_string")
                    result = await sender.send(text, request.get("mode", "replace"),
                                               request.get("confirm_replace") is True)
                    text = None
                    emit(**result.safe_dict())
                elif command == "observed":
                    # This records a human claim, not an automated visual test.
                    outcome = request.get("outcome")
                    if outcome not in ("exact", "mismatch", "not_visible", "unknown"):
                        raise ProbeError("invalid_visual_outcome")
                    sender.acknowledge_review()
                    emit(status="human_observation_recorded", outcome=outcome,
                         source="user_report")
                elif command == "remote":
                    action = request.get("action")
                    if action not in ("up", "down", "left", "right", "select", "menu", "home", "play_pause"):
                        raise ProbeError("unsupported_remote_action")
                    await asyncio.wait_for(getattr(atv.remote_control, action)(), 8)
                    emit(status="remote_request_completed", action=action,
                         visual_confirmation_required=True)
                else:
                    raise ProbeError("unknown_command")
            except ProbeError as error:
                emit(status="blocked", reason=str(error))
            except Exception as error:
                # Exception messages can quote a whole subscription URL or PIN.
                emit(status="error", error_type=type(error).__name__, detail="原始异常已隐藏，避免泄露输入内容。")
    except ProbeError as error:
        emit(status="blocked", reason=str(error))
        return 2
    except Exception as error:
        emit(status="session_failed", error_type=type(error).__name__,
             advice="检查网络、电视验证码和配对设置；不要重复发送结果未知的文字。")
        return 2
    finally:
        if pairing:
            await pairing.close()
        if atv:
            tasks = atv.close()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
        emit(status="closed", credentials_persisted=False)
    return 0


async def main():
    parser = argparse.ArgumentParser(description="Apple TV Companion 本地验证工具")
    parser.add_argument("action", choices=("scan", "session", "cases"))
    parser.add_argument("--host", help="Apple TV 的 IP；不指定时使用 mDNS")
    args = parser.parse_args()
    if args.action == "scan":
        devices = await discover(args.host)
        emit(status="scan_completed", devices=[device_summary(c) for c in devices])
        return 0 if devices else 2
    if args.action == "cases":
        emit(cases=[dict(name=k, characters=len(v), utf8_bytes=len(v.encode()))
                    for k, v in synthetic_cases().items()])
        return 0
    return await session(args)


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        sys.exit(130)
    except Exception as error:
        emit(status="failed", error_type=type(error).__name__)
        sys.exit(2)
