"""Writes the tombstone protobuf fixtures (debuggerd's tombstone.proto) of traceway/src/test/resources/exits/.

    python3 scripts/make-tombstone-fixtures.py traceway/src/test/resources/exits
"""
import struct, sys, os

def varint(n):
    n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

def key(field, wire): return varint((field << 3) | wire)
def vint(field, n): return key(field, 0) + varint(n)
def ld(field, data):
    if isinstance(data, str): data = data.encode()
    return key(field, 2) + varint(len(data)) + data
def f64(field, n): return key(field, 1) + struct.pack('<Q', n)
def f32(field, n): return key(field, 5) + struct.pack('<I', n)

def frame(rel_pc, file, fn=None, off=0, build_id=None, map_off=0, pc=None):
    m = vint(1, rel_pc) + vint(2, pc if pc is not None else rel_pc + 0x7a00000000) + vint(3, 0x7ffc001230)
    if fn: m += ld(4, fn)
    if off: m += vint(5, off)
    m += ld(6, file)
    if map_off: m += vint(7, map_off)
    if build_id: m += ld(8, build_id)
    return m

def register(name, value): return ld(1, name) + vint(2, value)

def thread(tid, name, frames, notes=()):
    m = vint(1, tid) + ld(2, name)
    for r in ('x0', 'x1', 'sp', 'pc'):
        m += ld(3, register(r, 0x7a12345678))
    for n in notes:
        m += ld(7, n)
    for f in frames:
        m += ld(4, f)
    # A memory dump next to the registers, as debuggerd writes one.
    m += ld(5, ld(1, 'x0') + vint(2, 0x7a12345000) + ld(3, b'\x00' * 64))
    return m

def entry(tid, t): return ld(16, vint(1, tid) + ld(2, t))

APP_LIB = '/data/app/~~kXy1Qw==/com.example.shop-Qp9zR2==/lib/arm64/libshop.so'

def segv():
    out = vint(1, 1)  # ARM64
    out += ld(2, 'google/oriole/oriole:14/AP2A.240805.005/12025142:user/release-keys')
    out += ld(3, '0')
    out += ld(4, '2026-11-20 10:15:02.123456789+0100')
    out += vint(5, 4321) + vint(6, 4330) + vint(7, 10234)
    out += ld(8, 'u:r:untrusted_app:s0:c512,c768')
    out += ld(9, 'com.example.shop')
    out += vint(20, 37)
    sig = vint(1, 11) + ld(2, 'SIGSEGV') + vint(3, 1) + ld(4, 'SEGV_MAPERR') + vint(8, 1)  # fault address 0: proto3 leaves it out
    out += ld(10, sig)
    out += ld(15, ld(1, 'null pointer dereference'))
    main = thread(4321, 'com.example.shop', [
        frame(0x9e3c8, '/apex/com.android.runtime/lib64/bionic/libc.so', '__epoll_pwait', 8, 'a87908b48b368e6282bcc9f34bcfc28c'),
        frame(0x17e84, '/system/lib64/libutils.so', 'android::Looper::pollInner(int)', 184, '8ccb4f5d1e5f2c4fa1c41d5b53e7c01a'),
    ])
    crashing = thread(4330, 'ImageDecoder', [
        frame(0x1a2b4, APP_LIB, 'Java_com_example_shop_NativeImages_decode', 20, '8e7f1c2a9b3d4e5f6a7b8c9d0e1f2a3b'),
        frame(0x1a0f0, APP_LIB, 'shop::Decoder::run(shop::Image const&)', 112, '8e7f1c2a9b3d4e5f6a7b8c9d0e1f2a3b'),
        frame(0x22a4, '/data/app/~~kXy1Qw==/com.example.shop-Qp9zR2==/base.apk', map_off=0x1000000),
        frame(0x2c4f8, '/apex/com.android.art/lib64/libart.so', 'art_quick_generic_jni_trampoline', 152, '61bf89c2fc8b7bdf2b0e8b2b7f8fc8ef'),
        frame(0x4d3c, '/system/framework/arm64/boot-framework.oat', 'android.view.View.performClick', 124, 'f0e1d2c3b4a5968778695a4b3c2d1e0f'),
    ], notes=['Note: this frame list is the crashing thread'])
    out += entry(4321, main)
    out += entry(4330, crashing)
    # Fields a later Android adds: skipped by number and wire type.
    out += f64(900, 0x1122334455667788) + f32(901, 0xAABBCCDD) + vint(902, 7) + ld(903, b'\x01\x02')
    out += ld(17, vint(1, 0x7a00000000) + vint(2, 0x7a00100000) + ld(7, APP_LIB))
    out += ld(18, ld(1, 'main') + ld(2, ld(1, '11-20 10:15:01.000') + ld(7, 'Decoding image 42')))
    out += ld(19, vint(1, 3) + ld(2, '/dev/null'))
    out += vint(22, 4096)
    return out

def abort32():
    out = vint(1, 0)  # ARM32
    out += vint(5, 977) + vint(6, 977)
    sig = vint(1, 6) + ld(2, 'SIGABRT') + vint(3, -6) + ld(4, 'SI_TKILL')
    out += ld(10, sig)
    out += ld(14, "FORTIFY: pthread_mutex_lock called on a destroyed mutex (0xe8b2c3d0)")
    out += entry(977, thread(977, 'main', [
        frame(0x6f5c8, '/apex/com.android.runtime/lib/bionic/libc.so', 'abort', 164, '61bf89c2fc8b7bdf'),
        frame(0x7b0a4, '/apex/com.android.runtime/lib/bionic/libc.so', '__fortify_fatal(char const*, ...)', 44),
        frame(0x1234, '/data/app/~~a==/com.example.shop-b==/lib/arm/libshop.so', 'shop::Cache::lock()', 16),
    ]))
    return out

here = sys.argv[1]
open(os.path.join(here, 'tombstone_segv_arm64.pb'), 'wb').write(segv())
open(os.path.join(here, 'tombstone_abort_arm.pb'), 'wb').write(abort32())
print('ok', len(segv()), len(abort32()))
