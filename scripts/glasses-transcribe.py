#!/usr/bin/env python3
"""Local, authenticated Whisper endpoint for glasses voice mode. No recordings are saved."""
import argparse
import hmac
import io
import json
from pathlib import Path
import threading
import os
import subprocess
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BYTES = 640_044  # 20 seconds, mono PCM16, 16 kHz, WAV header


def decode_wav(body):
    import numpy as np
    with wave.open(io.BytesIO(body), 'rb') as wav:
        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) != (1, 2, 16000):
            raise ValueError('Expected mono PCM16 at 16000 Hz')
        if not 4000 <= wav.getnframes() <= 320000:
            raise ValueError('Audio must be 0.25 to 20 seconds')
        samples = wav.readframes(320001)
        if len(samples) != wav.getnframes() * 2:
            raise ValueError('Truncated WAV')
    return np.frombuffer(samples, dtype='<i2').astype(np.float32) / 32768.0


def switch_owner(owner, adb):
    if owner not in ('official', 'openrayneo'):
        raise ValueError('Unknown glasses owner')
    def run(*arguments):
        result = subprocess.run([str(adb), *arguments], capture_output=True, text=True, timeout=12)
        if result.returncode:
            raise RuntimeError('ADB operation failed. Check the phone connection.')
        return result.stdout
    phones = [line.split()[0] for line in run('devices').splitlines()[1:]
              if len(line.split()) == 2 and line.split()[1] == 'device'
              and not line.startswith('emulator-')]
    if len(phones) != 1:
        raise RuntimeError('Connect exactly one physical phone in ADB.')
    serial = phones[0]
    permission_action = 'grant' if owner == 'official' else 'revoke'
    run('-s', serial, 'shell', 'pm', permission_action,
        'com.rayneo.venus.pub', 'android.permission.BLUETOOTH_CONNECT')
    if owner == 'openrayneo':
        run('-s', serial, 'shell', 'am', 'force-stop', 'com.rayneo.venus.pub')
    return {'owner': owner}


def make_handler(model, token, switch=None):
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = 'HTTP/1.0'

        def log_message(self, *_):
            pass  # Never log audio, transcripts, or bearer tokens.

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def respond(self, status, payload):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            return hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + token)

        def do_GET(self):
            if not self.authorized():
                self.respond(401, {'error': 'Unauthorized'})
            elif self.path == '/health':
                self.respond(200, {'ready': True, 'language': 'en', 'local': True})
            else:
                self.respond(404, {'error': 'Not found'})

        def do_POST(self):
            if not self.authorized():
                self.respond(401, {'error': 'Unauthorized'})
                return
            if self.path == '/switch':
                if switch is None:
                    self.respond(503, {'error': 'App switching is not configured on the PC'})
                    return
                try:
                    size = int(self.headers.get('Content-Length', '0'))
                    if not 0 < size <= 256 or self.headers.get('Transfer-Encoding'):
                        raise ValueError('Invalid switch request')
                    owner = json.loads(self.rfile.read(size)).get('owner')
                    if owner not in ('official', 'openrayneo'):
                        raise ValueError('Unknown glasses owner')
                    result = switch(owner)
                except (ValueError, TypeError, AttributeError):
                    self.respond(400, {'error': 'Choose official or openrayneo'})
                    return
                except (RuntimeError, OSError, subprocess.TimeoutExpired):
                    self.respond(503, {'error': 'App switch failed. Check the PC ADB connection and retry.'})
                    return
                self.respond(200, result)
                return
            if self.path != '/transcribe':
                self.respond(404, {'error': 'Not found'})
                return
            if self.headers.get('Transfer-Encoding'):
                self.respond(400, {'error': 'Content-Length required'})
                return
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 44 < length <= MAX_BYTES:
                    self.respond(413, {'error': 'Audio size outside allowed range'})
                    return
                body = self.rfile.read(length)
                if len(body) != length:
                    raise ValueError('Truncated request')
                audio = decode_wav(body)
            except (ValueError, wave.Error, EOFError, TimeoutError):
                self.respond(400, {'error': 'Invalid mono 16 kHz WAV'})
                return
            if not lock.acquire(blocking=False):
                self.respond(429, {'error': 'Transcription busy; retry a new utterance'})
                return
            try:
                segments, _ = model.transcribe(audio, language='en', beam_size=1,
                    condition_on_previous_text=False, vad_filter=True,
                    vad_parameters={'min_silence_duration_ms': 400})
                text = ' '.join(s.text.strip() for s in segments
                    if s.no_speech_prob < 0.6 and s.avg_logprob > -1.0).strip()
                self.respond(200, {'text': text})
            except Exception:
                self.respond(503, {'error': 'Local transcription failed'})
            finally:
                lock.release()

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--token-file', required=True, type=Path)
    parser.add_argument('--model', default='base.en')
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--port', type=int, default=4501)
    parser.add_argument('--device', choices=['cpu', 'cuda'], default='cpu')
    parser.add_argument('--adb', type=Path, default=Path(os.environ.get('LOCALAPPDATA', '')) / 'Android/Sdk/platform-tools/adb.exe')
    args = parser.parse_args()
    token = args.token_file.read_text().strip()
    if len(token) < 32 or not 1024 <= args.port <= 65535:
        parser.error('A valid token and unprivileged port are required')
    from faster_whisper import WhisperModel
    print('Loading local English speech model...', flush=True)
    model = WhisperModel(args.model, device=args.device,
        compute_type='int8' if args.device == 'cpu' else 'float16',
        cpu_threads=4, download_root=str(args.model_dir))
    server = ThreadingHTTPServer(('127.0.0.1', args.port), make_handler(model, token,
        lambda owner: switch_owner(owner, args.adb)))
    print(f'Local transcription ready on 127.0.0.1:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
