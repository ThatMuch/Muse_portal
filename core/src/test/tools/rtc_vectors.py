"""Génère les vecteurs média à partir du code du SDK (join.py, stun.py).

usage : python3 -I rtc_vectors.py /chemin/vers/muse-gadget-sdk > ../resources/rtc_sdk.json

Les valeurs tirées au sort sont figées. Le paquet « cryptography », utilisé seulement pour
l'identité DTLS remplacée ici, n'est pas nécessaire.
"""
import hashlib
import hmac
import json
import struct
import sys
import types

sys.path.insert(0, sys.argv[1] + "/linux/src")


class _Anything(types.ModuleType):
    def __getattr__(self, name):
        return object


for name in ("cryptography", "cryptography.x509", "cryptography.x509.oid", "cryptography.hazmat",
             "cryptography.hazmat.primitives", "cryptography.hazmat.primitives.asymmetric"):
    sys.modules[name] = _Anything(name)

from musegadget.rtc import join, stun  # noqa: E402

tokens = iter(["cufr", "server-ufrag-016", "client-pwd-" + "c" * 13, "server-pwd-" + "s" * 13])
identities = iter([("unused", "unused", "sha-256 AA:BB"), ("SERVER KEY PEM", "SERVER CERT PEM", "unused")])
numbers = iter([234567890, 41])
join._ice_token = lambda length: next(tokens)
join._dtls_identity = lambda: next(identities)
join.secrets = types.SimpleNamespace(token_bytes=lambda n: bytes(range(n)), randbelow=lambda n: next(numbers))
offer = join.build_join(thread_id="thread-7", edge_host="edge.example", edge_port=3478, vip_type=8, video=True)

w = join._Writer()
w.binary(1, offer.client_session_id)
w.i32(3, offer.transaction_id)
w.i32(4, 200)
w.out.append(0)
w._last = [0]
w.begin(2)
w.binary(1, "answer-ufrag")
w.binary(2, "192.0.2.7")
w.binary(3, "")
w.end()
w.out.append(0)
response = bytes(w.out)

txid = bytes(range(12))
stun.os = types.SimpleNamespace(urandom=lambda n: txid)
requests = {
    name: stun.binding_request("srv:cli", "secret-password", 1862270975, 0x0102030405060708, nominate=flag)[0].hex()
    for name, flag in (("check", False), ("nominate", True))
}
header = struct.pack("!HHI", stun.BINDING_SUCCESS, 24, stun.MAGIC) + txid
success = header + struct.pack("!HH", stun.MESSAGE_INTEGRITY, 20) + hmac.new(b"secret-password", header, hashlib.sha1).digest()
assert stun.parse_response(success, txid, "secret-password")

print(json.dumps({
    "join": {"offer": offer.payload.hex(), "transaction": offer.transaction_id,
             "session": offer.client_session_id, "response": response.hex()},
    "stun": {**requests, "success": success.hex(), "transaction": txid.hex()},
}, indent=1))
