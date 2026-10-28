import importlib.util
import io
import json
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request
import wave
from types import SimpleNamespace
from http.server import ThreadingHTTPServer

spec = importlib.util.spec_from_file_location('speech_server', Path(__file__).parents[1] / 'glasses-transcribe.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def wav_bytes(rate=16000, channels=1, frames=16000):
    output = io.BytesIO()
    with wave.open(output, 'wb') as stream:
        stream.setparams((channels, 2, rate, 0, 'NONE', ''))
        stream.writeframes(b'\0\0' * frames * channels)
    return output.getvalue()


class Model:
    def transcribe(self, audio, **options):
        return iter([
            SimpleNamespace(text='Codex, hello.', no_speech_prob=0.1, avg_logprob=-0.1),
            SimpleNamespace(text='hallucinated silence', no_speech_prob=0.9, avg_logprob=-0.1),
        ]), None


class EndpointTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.token = 'test-token-' * 5
        cls.switches = []
        def switch(owner):
            cls.switches.append(owner)
            return {'owner': owner}
        cls.server = ThreadingHTTPServer(('127.0.0.1', 0), module.make_handler(Model(), cls.token, switch))
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.url = f'http://127.0.0.1:{cls.server.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown(); cls.server.server_close(); cls.worker.join()

    def request(self, path, data=None, auth=True):
        headers = {'Authorization': 'Bearer ' + self.token} if auth else {}
        try:
            result = urllib.request.urlopen(urllib.request.Request(self.url + path, data, headers), timeout=5)
        except urllib.error.HTTPError as error:
            result = error
        with result:
            return result.code, json.load(result)

    def test_authentication_required_for_audio_and_health(self):
        self.assertEqual(self.request('/transcribe', wav_bytes(), False)[0], 401)
        self.assertEqual(self.request('/health', auth=False)[0], 401)

    def test_valid_audio_and_hallucination_filter(self):
        self.assertEqual(self.request('/transcribe', wav_bytes()), (200, {'text': 'Codex, hello.'}))

    def test_wrong_format_and_empty_audio_rejected(self):
        for data in (wav_bytes(rate=48000), wav_bytes(channels=2), b'not a wav' * 8):
            self.assertEqual(self.request('/transcribe', data)[0], 400)
        self.assertEqual(self.request('/transcribe', wav_bytes(frames=0))[0], 413)

    def test_limit_and_truncated_audio(self):
        self.assertEqual(self.request('/transcribe', b'x' * (module.MAX_BYTES + 1))[0], 413)
        self.assertEqual(self.request('/transcribe', wav_bytes()[:-100])[0], 400)

    def test_health(self):
        self.assertTrue(self.request('/health')[1]['ready'])

    def test_switch_requires_auth_and_rejects_arbitrary_commands(self):
        previous = len(self.switches)
        self.assertEqual(self.request('/switch', b'{"owner":"official"}', False)[0], 401)
        self.assertEqual(self.request('/switch', b'{"owner":"shell command"}')[0], 400)
        self.assertEqual(len(self.switches), previous)

    def test_switch_both_directions(self):
        for owner in ('official', 'openrayneo'):
            self.assertEqual(self.request('/switch', json.dumps({'owner': owner}).encode()), (200, {'owner': owner}))
            self.assertEqual(self.switches[-1], owner)


if __name__ == '__main__':
    unittest.main()
