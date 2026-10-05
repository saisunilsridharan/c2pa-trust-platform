"""Exercise supported formats using a running portal and generated fixtures.
Enable all listed formats in the UI before running. Requires FFmpeg for fixtures.
Credentials stay in memory. Uses synchronous signing; creates no saved jobs.
"""
import json
from pathlib import Path
import runpy
import struct
import subprocess
import tempfile
import wave
import zlib

FORMATS = {'jpg': 'image/jpeg', 'png': 'image/png', 'webp': 'image/webp',
           'tif': 'image/tiff', 'wav': 'audio/wav', 'mp3': 'audio/mpeg',
           'flac': 'audio/flac', 'mp4': 'video/mp4', 'pdf': 'application/pdf'}


def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data))


def fixtures(folder):
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 16, 16, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress((b'\0' + b'\xff\0\0' * 16) * 16)) + chunk(b'IEND', b'')
    (folder / 'sample.png').write_bytes(png)
    with wave.open(str(folder / 'sample.wav'), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(8000)
        output.writeframes(b'\0\0' * 1600)
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R /Resources << >> >>',
               b'<< /Length 0 >>\nstream\n\nendstream']
    pdf, offsets = b'%PDF-1.7\n', []
    for index, obj in enumerate(objects, 1):
        offsets.append(len(pdf))
        pdf += f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n'
    xref = len(pdf)
    pdf += b'xref\n0 5\n0000000000 65535 f \n'
    pdf += b''.join(f'{offset:010d} 00000 n \n'.encode() for offset in offsets)
    pdf += f'trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()
    (folder / 'sample.pdf').write_bytes(pdf)
    for source, extension in [('png', 'jpg'), ('png', 'webp'), ('png', 'tif'), ('wav', 'mp3'), ('wav', 'flac')]:
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(folder / f'sample.{source}'),
                        '-threads', '1', str(folder / f'sample.{extension}')], check=True, capture_output=True)
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-loop', '1', '-i', str(folder / 'sample.png'),
                    '-t', '0.2', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-threads', '1',
                    str(folder / 'sample.mp4')], check=True, capture_output=True)


def tamper(extension, content):
    altered = bytearray(content)
    if extension == 'jpg':
        offset = 2
        while offset < len(altered):
            assert altered[offset] == 255
            marker = altered[offset + 1]
            size = struct.unpack_from('!H', altered, offset + 2)[0]
            if marker == 219:
                index = offset + 5
                altered[index] = 1 if altered[index] != 1 else 2
                return bytes(altered)
            offset += 2 + size
        raise AssertionError('JPEG quantization table missing')
    elif extension == 'png':
        offset, changed = 8, False
        while offset < len(altered):
            size = struct.unpack_from('!I', altered, offset)[0]
            kind = altered[offset + 4:offset + 8]
            if kind == b'IDAT':
                data = bytearray(zlib.decompress(altered[offset + 8:offset + 8 + size]))
                data[1] ^= 1
                return bytes(altered[:offset]) + chunk(b'IDAT', zlib.compress(data)) + bytes(altered[offset + size + 12:])
            offset += size + 12
        raise AssertionError('No PNG image data')
    elif extension in ('wav', 'webp'):
        offset = 12
        index = None
        while offset + 8 <= len(altered):
            kind = altered[offset:offset + 4]
            size = struct.unpack_from('<I', altered, offset + 4)[0]
            if (extension == 'wav' and kind == b'data') or (extension == 'webp' and kind in (b'VP8 ', b'VP8L')):
                index = offset + 8 + (1 if extension == 'wav' else size - 2)
                break
            offset += 8 + size + size % 2
        assert index is not None
    elif extension == 'mp4':
        index = altered.find(b'mdat') + 12
        assert index >= 12
    elif extension == 'pdf':
        start = altered.find(b'/MediaBox')
        index = altered.find(b'100', start, start + 100)
        assert start >= 0 and index >= 0
        index += 2
    elif extension == 'tif':
        endian = '<' if altered[:2] == b'II' else '>'
        directory = struct.unpack_from(endian + 'I', altered, 4)[0]
        count = struct.unpack_from(endian + 'H', altered, directory)[0]
        index = None
        for entry in range(count):
            position = directory + 2 + 12 * entry
            tag, kind, count_values = struct.unpack_from(endian + 'HHI', altered, position)
            if tag == 273:
                value = struct.unpack_from(endian + 'I', altered, position + 8)[0]
                index = value if count_values == 1 else struct.unpack_from(endian + 'I', altered, value)[0]
                break
        assert index is not None
    else:
        index = len(altered) - 20
    altered[index] ^= 1
    return bytes(altered)


def run(base=None, token=None):
    helper = runpy.run_path(str(Path(__file__).with_name('smoke-signing.py')))
    request, upload = helper['request'], helper['upload']
    if base:
        request.__globals__['BASE'] = base
    if token:
        request.__globals__['TOKEN'] = token
    configuration = request('/portal/configuration')
    assert configuration['active'], 'Activate a profile through the UI first'
    assert set(FORMATS.values()).issubset(configuration['active']['formats']), 'Enable all test formats through the UI first'
    assert configuration['signingAvailable'], 'Configure a signing identity through the UI first'
    fields = {'creator': 'Format smoke test', 'title': 'Generated format fixture',
              'aiDisclosure': 'none', 'acknowledgePublicClaims': 'true'}
    with tempfile.TemporaryDirectory(prefix='c2pa-format-smoke-') as temporary:
        folder = Path(temporary)
        fixtures(folder)
        for extension, mime in FORMATS.items():
            # The helper deliberately uses a PNG filename/MIME for all uploads,
            # checking that the backend detects actual bytes rather than trusting metadata.
            signed = upload('/signing', (folder / f'sample.{extension}').read_bytes(), fields)
            report = json.loads(upload('/verification', signed))
            assert report['validation_state'] == 'Valid', (extension, report.get('validation_status'))
            resigned = upload('/signing', signed, fields)
            history = json.loads(upload('/verification', resigned))
            assert history['validation_state'] == 'Valid' and len(history['manifests']) >= 2
            changed = json.loads(upload('/verification', tamper(extension, signed)))
            assert changed['validation_state'] == 'Invalid', (extension, 'Modified content was accepted')
            print(f'{mime}: signing, verification, prior provenance and tamper detection passed.')


if __name__ == '__main__':
    run()
