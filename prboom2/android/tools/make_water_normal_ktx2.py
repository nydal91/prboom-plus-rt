# Minimal KTX2 writer: single level, VK_FORMAT_R8G8B8A8_UNORM, uncompressed
import struct, sys, math

def ktx2_rgba8(path, w, h, pixels, srgb=False):
    vk_format = 43 if srgb else 37  # R8G8B8A8_SRGB / _UNORM
    # Data Format Descriptor, basic block with 4 samples
    samples = b''
    for i, ch in enumerate([0, 1, 2, 15]):  # R, G, B, A
        qual = 0
        samples += struct.pack('<HBB', i * 8, 7, ch | qual) + b'\0\0\0\0' + struct.pack('<II', 0, 255)
    block_size = 24 + len(samples)
    basic = struct.pack('<IHH', 0, 2, block_size)
    basic += struct.pack('<BBBB', 1, 1, 2 if srgb else 1, 0)  # RGBSDA, BT709, transfer, flags
    basic += b'\0\0\0\0'                                     # texel block dims (1x1x1x1 -> 0)
    basic += bytes([4, 0, 0, 0, 0, 0, 0, 0])                # bytesPlane
    basic += samples
    dfd = struct.pack('<I', 4 + len(basic)) + basic

    header_size = 12 + 9 * 4 + 4 * 4 + 2 * 8
    level_index_size = 3 * 8
    dfd_off = header_size + level_index_size
    data_off = dfd_off + len(dfd)
    data_off = (data_off + 15) // 16 * 16
    data = bytes(pixels)
    assert len(data) == w * h * 4

    out = bytearray()
    out += bytes([0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A])
    out += struct.pack('<9I', vk_format, 1, w, h, 0, 0, 1, 1, 0)
    out += struct.pack('<4I', dfd_off, len(dfd), 0, 0)
    out += struct.pack('<2Q', 0, 0)
    out += struct.pack('<3Q', data_off, len(data), len(data))
    out += dfd
    out += b'\0' * (data_off - len(out))
    out += data
    open(path, 'wb').write(out)

# flat-ish water normal map
w = h = 64
px = []
for y in range(h):
    for x in range(w):
        nx = 0.15 * math.sin(x / w * 2 * math.pi * 2)
        ny = 0.15 * math.cos(y / h * 2 * math.pi * 2)
        nz = math.sqrt(max(0.0, 1 - nx * nx - ny * ny))
        px += [int((nx * 0.5 + 0.5) * 255), int((ny * 0.5 + 0.5) * 255), int((nz * 0.5 + 0.5) * 255), 255]
ktx2_rgba8(sys.argv[1] + "/WaterNormal_n.ktx2", w, h, px)
print('ok')
