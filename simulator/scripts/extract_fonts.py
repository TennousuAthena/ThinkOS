#!/usr/bin/env python3
"""Read selected fonts from an existing local ext4 system image. Never mounts or modifies it."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import io


class Ext4:
    def __init__(self, source):
        self.file = open(source, 'rb')
        self.read_bytes = 0
        try:
            s = self.read(1024, 1024)
        except Exception:
            self.file.close()
            raise
        if struct.unpack_from('<H', s, 56)[0] != 0xef53:
            self.file.close()
            raise ValueError('not an ext4 image')
        self.block = 1024 << struct.unpack_from('<I', s, 24)[0]
        self.per_group = struct.unpack_from('<I', s, 40)[0]
        self.inode_size = struct.unpack_from('<H', s, 88)[0]
        self.descriptor = max(32, struct.unpack_from('<H', s, 254)[0])
        if self.block not in (1024, 2048, 4096) or not 128 <= self.inode_size <= self.block or not 1 <= self.per_group <= 2**20 or not 32 <= self.descriptor <= self.block:
            self.file.close()
            raise ValueError('unsupported ext4 geometry')

    def read(self, offset, size):
        if offset < 0 or not 0 <= size <= 64 * 1024 * 1024:
            raise ValueError('invalid image read')
        self.read_bytes += size
        if self.read_bytes > 512 * 1024 * 1024:
            raise ValueError('image read budget exceeded')
        self.file.seek(offset)
        value = self.file.read(size)
        if len(value) != size:
            raise ValueError('truncated image')
        return value

    def inode(self, number):
        group, index = divmod(number - 1, self.per_group)
        d = self.read((2 if self.block == 1024 else 1) * self.block + group * self.descriptor, self.descriptor)
        table = struct.unpack_from('<I', d, 8)[0]
        if self.descriptor >= 64:
            table |= struct.unpack_from('<I', d, 40)[0] << 32
        inode = self.read(table * self.block + index * self.inode_size, self.inode_size)
        flags = struct.unpack_from('<I', inode, 32)[0]
        if not flags & 0x80000:
            raise ValueError('only extent-based inodes supported')
        size = struct.unpack_from('<I', inode, 4)[0] | struct.unpack_from('<I', inode, 108)[0] << 32
        if size > 32 * 1024 * 1024:
            raise ValueError('font/inode exceeds 32 MiB')
        data = bytearray(size)
        nodes = 0
        def extents(node, level=0):
            nonlocal nodes
            nodes += 1
            if nodes > 4096:
                raise ValueError('extent node budget exceeded')
            if level > 5:
                raise ValueError('extent depth exceeded')
            magic, entries, maximum, depth = struct.unpack_from('<HHHH', node)
            if magic != 0xf30a or entries > maximum or 12 + entries * 12 > len(node):
                raise ValueError('invalid extent header')
            for index in range(entries):
                offset = 12 + index * 12
                logical = struct.unpack_from('<I', node, offset)[0]
                if depth:
                    low, high = struct.unpack_from('<IH', node, offset + 4)
                    extents(self.read((low | high << 32) * self.block, self.block), level+1)
                else:
                    length, high, low = struct.unpack_from('<HHI', node, offset + 4)
                    if length > 32768:
                        continue  # Uninitialized extents are zero-filled.
                    start = logical * self.block
                    count = min(length * self.block, max(0, size-start))
                    if count:
                        data[start:start+count] = self.read((low | high << 32) * self.block, count)
        extents(inode[40:100])
        return bytes(data)

    def lookup(self, path):
        number = 2
        for part in path.strip('/').split('/'):
            data = self.inode(number)
            cursor, found = 0, None
            while cursor + 8 <= len(data):
                ino, length, name_len, _ = struct.unpack_from('<IHBB', data, cursor)
                if length < 8 or cursor + length > len(data) or name_len > length-8:
                    raise ValueError('invalid directory record')
                name = data[cursor+8:cursor+8+name_len].decode('utf-8')
                if ino and name == part:
                    found = ino
                    break
                cursor += length
            if found is None:
                raise FileNotFoundError(path)
            number = found
        return self.inode(number)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'assets/fonts')
    args = parser.parse_args()
    image = Ext4(args.image)
    args.output.mkdir(parents=True, exist_ok=True)
    fonts = []
    for filename, family in [('Roboto-Regular.ttf', 'Roboto'), ('Roboto-Light.ttf', 'Roboto'),
                             ('NotoNaskhArabicUI-Regular.ttf', 'Noto Naskh Arabic UI'),
                             ('NotoSansDevanagariUI-Regular.ttf', 'Noto Sans Devanagari UI'),
                             ('NotoSansBengaliUI-Regular.ttf', 'Noto Sans Bengali UI')]:
        data = image.lookup('fonts/' + filename)
        (args.output / filename).write_bytes(data)
        fonts.append({'family': family, 'file': filename, 'sha256': hashlib.sha256(data).hexdigest()})
    # CanvasKit's TypefaceFontProvider has no TTC face-index argument. Preserve the
    # correct Android zh-Hans/zh-Hant faces instead of silently choosing Japanese index 0.
    from fontTools.ttLib import TTFont
    from fontTools import version as fonttools_version
    collection = image.lookup('fonts/NotoSansCJK-Regular.ttc')
    for index, suffix in [(2, 'SC'), (3, 'TC')]:
        font = TTFont(io.BytesIO(collection), fontNumber=index, recalcTimestamp=False)
        output = io.BytesIO()
        font.save(output)
        font.close()
        filename = f'NotoSansCJK-{suffix}.otf'
        data = output.getvalue()
        (args.output / filename).write_bytes(data)
        fonts.append({'family': f'Noto Sans CJK {suffix}', 'file': filename, 'sha256': hashlib.sha256(data).hexdigest(),
                      'source_sha256': hashlib.sha256(collection).hexdigest(), 'collection_index': index})
    config = image.lookup('etc/fonts.xml')
    (args.output / 'fonts.xml').write_bytes(config)
    manifest = {'source': str(args.image), 'calibrated': False, 'fonts': fonts,
                'fonttools_version': fonttools_version,
                'configuration_sha256': hashlib.sha256(config).hexdigest()}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    image.file.close()
    print(f'Extracted {len(fonts)} local ROM font faces; live-device visual calibration remains required.')


if __name__ == '__main__':
    main()
