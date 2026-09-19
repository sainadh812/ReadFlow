"""Download the licensed, fixed Pocket voice used in the prototype."""
from fetch_models import ROOT, fetch

fetch("https://huggingface.co/kyutai/tts-voices/resolve/323332d33f997de8394f24a193e1a76df720e01a/alba-mackenna/casual.wav", ROOT / "voices/alba.wav", "46264e83cb99115c3d210260e029117566d9c64f20266d10daa78107759ede3e")
