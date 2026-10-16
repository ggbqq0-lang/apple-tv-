"""Generate public deterministic reference vectors using pyatv, not Android code."""
import hashlib
import plistlib
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey
from cryptography.hazmat.primitives import serialization
from pyatv.auth.hap_srp import hkdf_expand
from pyatv.protocols.companion.plist_payloads import get_rti_input_text_payload
from pyatv.support import opack
from pyatv.support.chacha20 import Chacha20Cipher
from srptools import SRPClientSession, SRPContext, SRPServerSession, constants

from .core import synthetic_cases


def main():
    values = {}
    context = SRPContext("Pair-Setup", "1111", prime=constants.PRIME_3072,
                         generator=constants.PRIME_3072_GEN, hash_func=hashlib.sha512)
    for index, salt in enumerate((bytes(range(1,17)), bytes(range(16)))):
        client_private = bytes(range(32))
        verifier = context.get_common_password_verifier(context.get_common_password_hash(salt))
        server = SRPServerSession(context, hex(verifier)[2:], private=(b"\xaa"*32).hex())
        client = SRPClientSession(context, private=client_private.hex())
        client.process(server.public, salt.hex())
        for key, value in {"salt":salt.hex(), "private":client_private.hex(), "B":server.public,
                           "A":client.public, "K":client.key.decode(), "M1":client.key_proof.decode(),
                           "M2":client.key_proof_hash.decode()}.items():
            values[f"srp{index}.{key}"] = value
    uuid = bytes(range(16))
    cases = synthetic_cases()
    values["case.count"] = str(len(cases))
    for i, (name, text) in enumerate(cases.items()):
        values[f"case{i}.name"] = name
        values[f"case{i}.text"] = text.encode().hex()
        values[f"case{i}.rti"] = get_rti_input_text_payload(uuid,text).hex()
    values["session.archive"] = plistlib.dumps({"$objects":["$null", uuid,
        {"docSt":plistlib.UID(3)},{"contextBeforeInput":plistlib.UID(4)},"中文&%2F"],
        "$top":{"sessionUUID":plistlib.UID(1),"documentState":plistlib.UID(2)},"$archiver":"RTIKeyedArchiver","$version":100000},fmt=plistlib.FMT_BINARY).hex()
    values["opack.references"] = opack.pack({"repeated":["same-string"]*40,"中文":"Café😀","data":uuid,"repeat2":"same-string"}).hex()
    key1,key2=bytes(range(32)),bytes(reversed(range(32)))
    cipher=Chacha20Cipher(key1,key2,nonce_length=12)
    payload=opack.pack({"_i":"_tiStart","_t":2})
    header=b"\x08"+(len(payload)+16).to_bytes(3,"big")
    values["frame.payload"]=payload.hex()
    for i in range(2): values[f"frame{i}"]=(header+cipher.encrypt(payload,aad=header)).hex()
    values["hkdf"]=hkdf_expand("","ClientEncrypt-main",bytes(range(32))).hex()
    seed=bytes(range(32))
    raw=dict(encoding=serialization.Encoding.Raw,format=serialization.PublicFormat.Raw)
    values["ed.public"]=Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(**raw).hex()
    values["ed.signature"]=Ed25519PrivateKey.from_private_bytes(seed).sign(b"fixture-message").hex()
    values["x.public"]=X25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(**raw).hex()
    path=Path("protocol/src/test/resources/reference.properties")
    path.write_text("# Public test material only; no actual user credentials.\n"+"\n".join(f"{k}={v}" for k,v in values.items())+"\n")
    print(f"Wrote {len(values)} public reference fields")


if __name__=="__main__":main()
