#!/usr/bin/env python3
"""Rebuild the raw /vendor partition image that a Lineage OTA zip writes (MIT).

The Lineage package carries its own vendor image as block data (vendor.new.dat.br + vendor.transfer.list), so a golden image
without a vendor partition boots only on a machine that already ran that package. This script decompresses the block data
and lays the blocks out the way the updater would, for a transfer list of version 4 made of new/zero/erase commands only.

  python3 -I tools/rebuild_vendor_image.py ZIP OUTPUT_DIR [--partition-bytes 402653184]

Reads one local zip (already hash-checked by the owner), runs the system `brotli` binary on a file it extracted itself, and
writes OUTPUT_DIR/vendor.img. It never touches a device.
"""
import argparse
import os
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

BLOCK = 4096


def ranges(spec):
    numbers = list(map(int, spec.split(',')))
    if numbers[0] != len(numbers) - 1 or numbers[0] % 2:
        raise ValueError('Malformed range list')
    return [(numbers[i], numbers[i + 1]) for i in range(1, len(numbers), 2)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('zip')
    parser.add_argument('output_dir')
    parser.add_argument('--partition-bytes', type=int, default=0x18000000)
    args = parser.parse_args()
    out_dir = Path(args.output_dir)
    out_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    with zipfile.ZipFile(args.zip) as package:
        transfer = package.read('vendor.transfer.list').decode()
        (out_dir / 'vendor.new.dat.br').write_bytes(package.read('vendor.new.dat.br'))
    subprocess.run(['brotli', '-d', '-f', str(out_dir / 'vendor.new.dat.br'), '-o', str(out_dir / 'vendor.new.dat')], check=True)
    lines = transfer.split('\n')
    if int(lines[0]) != 4:
        sys.exit('Only transfer list version 4 is supported')
    new = []
    top = 0
    for line in lines[4:]:
        if not line.strip():
            continue
        name, spec = line.split(' ', 1)
        parsed = ranges(spec)
        if name == 'new':
            new += parsed
        elif name not in ('zero', 'erase'):
            sys.exit('Unsupported transfer command: ' + name)
        top = max(top, max(end for _, end in parsed))
    data = out_dir / 'vendor.new.dat'
    if sum(end - start for start, end in new) * BLOCK != data.stat().st_size:
        sys.exit('New block count does not match the decompressed data')
    blocks = args.partition_bytes // BLOCK
    if top > blocks:
        sys.exit('Transfer list reaches past the partition')
    image = out_dir / 'vendor.img'
    with data.open('rb') as source, image.open('wb') as target:
        target.truncate(blocks * BLOCK)
        for start, end in new:
            target.seek(start * BLOCK)
            target.write(source.read((end - start) * BLOCK))
        if source.read(1):
            sys.exit('Unused block data left over')
    with image.open('rb') as check:
        check.seek(1024)
        superblock = check.read(1024)
    if struct.unpack_from('<H', superblock, 56)[0] != 0xEF53 or 1024 << struct.unpack_from('<I', superblock, 24)[0] != BLOCK:
        sys.exit('Not an ext4 image with 4 KiB blocks')
    print(image)


if __name__ == '__main__':
    main()
