"""Protocol self-check for the Kotlin proxy core.

Mirrors the handshake/crypto logic of android/.../core in Python and verifies
it against the obfuscated2 rules for all three transport tags:
client handshake parsing, relay init generation, and both re-encryption
directions. Run: pip install cryptography && python tools/verify_crypto.py
"""
import os, hashlib
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

TAG_ABR = b'\xef\xef\xef\xef'
TAG_INT = b'\xee\xee\xee\xee'
TAG_PAD = b'\xdd\xdd\xdd\xdd'

class AesCtr:
    def __init__(self, key, iv):
        self.c = Cipher(algorithms.AES(key), modes.CTR(iv)).encryptor()
    def process(self, data):
        return self.c.update(data) if data else b''
    def skip(self, n):
        if n > 0: self.c.update(b'\x00' * n)

def sha256(*parts):
    h = hashlib.sha256()
    for p in parts: h.update(p)
    return h.digest()

def keystream(key, iv, n):
    return AesCtr(key, iv).process(b'\x00' * n)

def parse(wire, secret):
    prekey_iv = wire[8:56]
    key = sha256(prekey_iv[:32], secret)
    iv = prekey_iv[32:48]
    dec = AesCtr(key, iv).process(wire)
    tag = dec[56:60]
    if tag not in (TAG_ABR, TAG_INT, TAG_PAD): return None
    raw = int.from_bytes(dec[60:62], 'little', signed=True)
    dc = abs(raw)
    if dc == 0: return None
    return dict(dc=dc, media=raw < 0, tag=tag, prekey_iv=prekey_iv)

RESERVED_STARTS = [b'HEAD', b'POST', b'GET ', TAG_INT, TAG_PAD, b'\x16\x03\x01\x02', b'OPTI']
def gen_relay_init(tag, dc_idx):
    while True:
        init = bytearray(os.urandom(64))
        if init[0] == 0xEF: continue
        if bytes(init[:4]) in RESERVED_STARTS: continue
        if init[4:8] == b'\x00\x00\x00\x00': continue
        break
    ks = keystream(bytes(init[8:40]), bytes(init[40:56]), 64)
    tail = tag + dc_idx.to_bytes(2, 'little', signed=True) + os.urandom(2)
    result = bytearray(init)
    for i in range(8):
        result[56+i] = tail[i] ^ ks[56+i]
    return bytes(result)

class Ctx:
    def __init__(self, hs, secret, relay_init):
        self.clt_dec = AesCtr(sha256(hs['prekey_iv'][:32], secret), hs['prekey_iv'][32:48]); self.clt_dec.skip(64)
        rev = hs['prekey_iv'][::-1]
        self.clt_enc = AesCtr(sha256(rev[:32], secret), rev[32:48])
        self.tg_enc = AesCtr(relay_init[8:40], relay_init[40:56]); self.tg_enc.skip(64)
        rt = relay_init[8:56][::-1]
        self.tg_dec = AesCtr(rt[:32], rt[32:48])
    def client_to_tg(self, data):
        plain = self.clt_dec.process(data)
        return plain, self.tg_enc.process(plain)
    def tg_to_client(self, data):
        return self.clt_enc.process(self.tg_dec.process(data))

SECRET = os.urandom(16)

def make_client_init(tag, dc_idx):
    """obfuscated2 client: 0..55 on the wire as-is, tail encrypted."""
    init = bytearray(os.urandom(64))
    key, iv = sha256(bytes(init[8:40]), SECRET), bytes(init[40:56])
    ks = keystream(key, iv, 64)
    init[56:60] = tag
    init[60:62] = dc_idx.to_bytes(2, 'little', signed=True)
    wire = bytes(init[:56]) + bytes(init[i] ^ ks[i] for i in range(56, 64))
    return wire, bytes(init)

for tag, dc_idx, name in [(TAG_ABR, 2, 'abridged/DC2'), (TAG_INT, -4, 'intermediate/media DC4'), (TAG_PAD, 2, 'padded/DC2')]:
    wire, plain_init = make_client_init(tag, dc_idx)
    hs = parse(wire, SECRET)
    assert hs and hs['tag'] == tag and abs(dc_idx) == hs['dc'] and hs['media'] == (dc_idx < 0), (name, hs)
    assert parse(wire, os.urandom(16)) is None, 'wrong secret accepted!'

    relay = gen_relay_init(tag, dc_idx)
    relay_key, relay_iv = relay[8:40], relay[40:56]
    rd = AesCtr(relay_key, relay_iv).process(relay)
    assert rd[56:60] == tag and int.from_bytes(rd[60:62], 'little', signed=True) == dc_idx, name + ' relay init'

    ctx = Ctx(hs, SECRET, relay)

    client_enc = AesCtr(sha256(plain_init[8:40], SECRET), plain_init[40:56]); client_enc.skip(64)
    payload = os.urandom(1000)
    plain, to_relay = ctx.client_to_tg(client_enc.process(payload))
    assert plain == payload, name + ' client->tg plain'
    relay_dec = AesCtr(relay_key, relay_iv); relay_dec.skip(64)
    assert relay_dec.process(to_relay) == payload, name + ' relay decryption'

    rev_relay = relay[8:56][::-1]
    wired_back = AesCtr(rev_relay[:32], rev_relay[32:48]).process(os.urandom(700))
    rev_client = plain_init[8:56][::-1]
    answer = AesCtr(rev_client[:32], rev_client[32:48]).process(ctx.tg_to_client(wired_back))
    assert len(answer) == 700, name + ' relay->client'
    print('crypto OK:', name)
