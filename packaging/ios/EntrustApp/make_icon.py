# 앱 아이콘 3개(1024px PNG)를 만든다 — 외부 라이브러리 없이(맥의 기본 python3). 각 앱 Assets.xcassets/AppIcon.appiconset/icon-1024.png
import struct, zlib
S = 1024

def ticket(x, y, bg):
    """표 모양(둥근 사각형 + 좌우 반원 홈 + 절취선) 안이면 True"""
    x0, y0, x1, y1, rr = 232, 300, 792, 724, 56
    if not (x0 <= x < x1 and y0 <= y < y1): return False
    cx = min(max(x, x0 + rr), x1 - rr); cy = min(max(y, y0 + rr), y1 - rr)
    if (x - cx) ** 2 + (y - cy) ** 2 > rr * rr: return False
    for hx in (232, 792):
        if (x - hx) ** 2 + (y - 512) ** 2 <= 44 * 44: return False
    if 613 <= x < 625 and 340 <= y < 684 and (y - 340) % 46 < 24: return False
    return True

def entrust(x, y):
    BG, FG = (0x1F, 0x45, 0xC9), (0xFF, 0xFF, 0xFF)
    if not ticket(x, y, BG): return BG
    for sx, sy in [(312, 392), (460, 392), (312, 540), (386, 466), (460, 540), (386, 392)]:
        if sx <= x < sx + 60 and sy <= y < sy + 60: return BG
    return FG

def ticket24(x, y):
    BG, FG = (0xEE, 0x2E, 0x3A), (0xFF, 0xFF, 0xFF)
    # 큰 '24' 대신 단순한 막대 두 개 + 원(읽기 쉬운 기호)
    if not ticket(x, y, BG): return BG
    if (x - 400) ** 2 + (y - 512) ** 2 <= 90 ** 2 and (x - 400) ** 2 + (y - 512) ** 2 >= 50 ** 2: return BG
    if 680 <= x < 720 and 380 <= y < 644: return BG
    return FG

def admin(x, y):
    BG, FG = (0x14, 0x17, 0x1C), (0x6C, 0x8D, 0xFF)
    if not ticket(x, y, BG): return BG
    # 위치 표시(핀)
    if (x - 422) ** 2 + (y - 470) ** 2 <= 36 ** 2: return FG          # 핀 가운데 구멍
    if (x - 422) ** 2 + (y - 470) ** 2 <= 92 ** 2: return BG
    if 500 <= y < 640 and abs(x - 422) <= (640 - y) * 0.62: return BG
    return FG

def png(path, fn):
    rows = bytearray()
    for y in range(S):
        rows.append(0)
        for x in range(S): rows.extend(fn(x, y))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', S, S, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(bytes(rows), 9)) + chunk(b'IEND', b'')
    open(path, 'wb').write(data)
    print('icon', path, len(data))

png('Sources/Assets.xcassets/AppIcon.appiconset/icon-1024.png', entrust)
png('Shop/Assets.xcassets/AppIcon.appiconset/icon-1024.png', ticket24)
png('Admin/Assets.xcassets/AppIcon.appiconset/icon-1024.png', admin)
