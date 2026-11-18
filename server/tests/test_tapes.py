"""A tape sent whole to this machine (tapes.py). Run: uv run python -m unittest discover tests"""
import asyncio
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve

from croissant_server.tapes import Tapes, clean_title

CHUNK = 1024


class TitleTest(unittest.TestCase):
    def test_a_title_loses_what_a_file_system_refuses(self):
        self.assertEqual(clean_title("Job: interview / round 2?"), "Job interview round 2")
        self.assertEqual(clean_title("..hidden"), "hidden")
        self.assertEqual(clean_title(""), "Tape")
        self.assertEqual(clean_title(None), "Tape")
        self.assertEqual(len(clean_title("x" * 300)), 80)


class UploadTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.folder = Path(tmp.name) / "R1 Tapes"
        self.tapes = Tapes(self.folder, 100_000, CHUNK, 4096)
        self.left = asyncio.Queue()

        async def handler(ws):
            try:
                await self.tapes.receive(ws)
            finally:
                self.left.put_nowait(None)

        server = await serve(handler, "127.0.0.1", 0, max_size=4 * 1024 * 1024)
        self.addAsyncCleanup(server.wait_closed)
        self.addCleanup(server.close)
        self.port = server.sockets[0].getsockname()[1]

    async def upload(self, data: bytes, **about):
        frames = []
        async with connect(f"ws://127.0.0.1:{self.port}/tape") as ws:
            await ws.send(json.dumps({"type": "begin", "size": len(data),
                                      "sha256": hashlib.sha256(data).hexdigest(), **about}))
            frames.append(json.loads(await ws.recv()))
            sent = 0
            while frames[-1]["type"] in ("ready", "got") and sent < len(data):
                await ws.send(data[sent:sent + CHUNK])
                sent += CHUNK
                frames.append(json.loads(await ws.recv()))
            if frames[-1]["type"] == "got":
                frames.append(json.loads(await ws.recv()))
        await asyncio.wait_for(self.left.get(), 5)
        return frames

    async def test_a_tape_is_kept_under_its_start_and_title(self):
        data = bytes(range(256)) * 10
        frames = await self.upload(data, tape="20261118-143005", title="Interview")
        self.assertEqual(frames[0], {"type": "ready", "name": "2026-11-18 1430 Interview.wav"})
        self.assertEqual(frames[-1]["type"], "saved")
        self.assertEqual((self.folder / "2026-11-18 1430 Interview.wav").read_bytes(), data)

    async def test_the_same_tape_twice_takes_a_suffix(self):
        for _ in range(2):
            await self.upload(b"abc", tape="20261118-143005", title="Tape 3")
        self.assertEqual(sorted(os.listdir(self.folder)), ["2026-11-18 1430 Tape 3-2.wav", "2026-11-18 1430 Tape 3.wav"])

    async def test_a_begin_without_the_tape_s_name_is_refused(self):
        frames = await self.upload(b"abc", tape="../../etc", title="x")
        self.assertEqual(frames[0]["type"], "error")
        self.assertIn("yyyyMMdd-HHmmss", frames[0]["error"])
        self.assertEqual(os.listdir(self.folder), [])


if __name__ == "__main__":
    unittest.main()
