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


def variant_fixtures(folder):
    """Content variants decoded independently of the C2PA SDK."""
    from PIL import Image
    from pypdf import PdfWriter
    from pypdf.generic import DictionaryObject, NameObject, DecodedStreamObject
    rows = []
    image = Image.new('RGBA', (48, 32), (30, 120, 220, 80))
    for name, options in [('alpha.png', {}), ('alpha.webp', {'lossless': True})]:
        image.save(folder / name, **options); rows.append(name)
    image.convert('RGB').save(folder / 'progressive.jpg', progressive=True, quality=85); rows.append('progressive.jpg')
    image.convert('P', palette=Image.Palette.ADAPTIVE).save(folder / 'palette.png'); rows.append('palette.png')
    grayscale = Image.new('I;16', (48, 32), 40000)
    grayscale.save(folder / 'gray16.png'); rows.append('gray16.png')
    image.convert('RGB').save(folder / 'multipage.tif', save_all=True, append_images=[Image.new('RGB',(32,48),'green')], compression='tiff_lzw'); rows.append('multipage.tif')
    with wave.open(str(folder / 'stereo.wav'), 'wb') as output:
        output.setnchannels(2); output.setsampwidth(2); output.setframerate(48000); output.writeframes(b'\x01\x00\x02\x00' * 9600)
    rows.append('stereo.wav')
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(folder/'stereo.wav'),'-c:a','libmp3lame','-q:a','4','-threads','1',str(folder/'stereo-vbr.mp3')],check=True,capture_output=True);rows.append('stereo-vbr.mp3')
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(folder/'stereo.wav'),'-threads','1',str(folder/'stereo.flac')],check=True,capture_output=True);rows.append('stereo.flac')
    subprocess.run(['ffmpeg','-v','error','-y','-loop','1','-i',str(folder/'sample.png'),'-i',str(folder/'stereo.wav'),'-t','0.2','-c:v','libx264','-pix_fmt','yuv420p','-c:a','aac','-movflags','frag_keyframe+empty_moov','-threads','1',str(folder/'fragmented.mp4')],check=True,capture_output=True);rows.append('fragmented.mp4')
    writer = PdfWriter()
    for index in range(3):
        page = writer.add_blank_page(width=200+index*10, height=300)
        font = DictionaryObject({NameObject('/Type'):NameObject('/Font'),NameObject('/Subtype'):NameObject('/Type1'),NameObject('/BaseFont'):NameObject('/Helvetica')})
        page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'):DictionaryObject({NameObject('/F1'):writer._add_object(font)})})
        stream=DecodedStreamObject();stream.set_data(f'BT /F1 12 Tf 20 50 Td (Portal page {index+1}) Tj ET'.encode())
        page[NameObject('/Contents')]=writer._add_object(stream.flate_encode())
    writer.add_metadata({'/Title':'Compressed multipage interoperability fixture'})
    with (folder/'multipage.pdf').open('wb') as output:writer.write(output)
    rows.append('multipage.pdf')
    return rows


def tiff_image_directory_copy(content):
    """Detach only the final C2PA-only IFD in a temporary decoding copy (C2PA 2.4 A.3.6)."""
    if content[:4] not in (b'II*\0', b'MM\0*'): raise ValueError('Classic TIFF header required')
    order = '<' if content[:2] == b'II' else '>'
    offset = struct.unpack_from(order+'I', content, 4)[0]
    seen = set(); previous_next = None
    while offset:
        if offset in seen or len(seen) >= 10000: raise ValueError('Invalid TIFF directory chain')
        seen.add(offset)
        if offset < 8 or offset+2 > len(content): raise ValueError('Invalid TIFF directory offset')
        count = struct.unpack_from(order+'H', content, offset)[0]
        next_position = offset+2+12*count
        if next_position+4 > len(content): raise ValueError('Truncated TIFF directory')
        next_offset = struct.unpack_from(order+'I', content, next_position)[0]
        if count == 1:
            tag, kind, length, payload = struct.unpack_from(order+'HHII',content,offset+2)
            if tag == 0xcd41:
                if kind != 7 or length <= 4 or payload+length > len(content) or next_offset != 0 or previous_next is None: raise ValueError('Invalid C2PA-only TIFF directory')
                result=bytearray(content);struct.pack_into(order+'I',result,previous_next,0);return bytes(result)
        previous_next=next_position;offset=next_offset
    return content


def decoded_content(extension, content):
    """Compare rendered/decoded content rather than mutable metadata/manifest bytes."""
    import io
    import hashlib
    if extension in ('png','jpg','webp','tif'):
        from PIL import Image, ImageSequence
        with Image.open(io.BytesIO(tiff_image_directory_copy(content) if extension == 'tif' else content)) as image:
            return [(frame.size, frame.mode if frame.mode.startswith('I') else 'RGBA', hashlib.sha256(frame.tobytes() if frame.mode.startswith('I') else frame.convert('RGBA').tobytes()).hexdigest()) for frame in ImageSequence.Iterator(image)]
    if extension == 'pdf':
        from pypdf import PdfReader
        reader=PdfReader(io.BytesIO(content),strict=True)
        return [(tuple(float(v) for v in page.mediabox),page.extract_text(),page.get_contents().get_data() if page.get_contents() else b'') for page in reader.pages]
    with tempfile.TemporaryDirectory(prefix='c2pa-independent-decode-') as temporary:
        source=Path(temporary)/('content.'+extension);source.write_bytes(content)
        if extension == 'mp4':
            result=subprocess.run(['ffmpeg','-v','error','-i',str(source),'-map','0:v:0','-f','framemd5','-'],check=True,capture_output=True,timeout=30)
            video=tuple(line for line in result.stdout.splitlines() if not line.startswith(b'#'))
            result=subprocess.run(['ffprobe','-v','error','-show_entries','stream=codec_type','-of','json',str(source)],check=True,capture_output=True,timeout=15)
            if not any(s['codec_type']=='audio' for s in json.loads(result.stdout)['streams']):return video
        else:video=None
        audio=subprocess.run(['ffmpeg','-v','error','-i',str(source),'-map','0:a:0','-f','s16le','-acodec','pcm_s16le','-'],check=True,capture_output=True,timeout=30).stdout
        return video,hashlib.sha256(audio).hexdigest()
