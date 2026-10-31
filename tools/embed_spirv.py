"""Embed a locally compiled diagnostic shader, preserving SPIR-V word alignment."""
import pathlib
import struct
import sys

source, destination = map(pathlib.Path, sys.argv[1:])
data = source.read_bytes()
if len(data) % 4 or data[:4] != b"\x03\x02\x23\x07":
    raise ValueError("Invalid SPIR-V header/alignment")
words = struct.unpack(f"<{len(data) // 4}I", data)
destination.write_text(
    "#include <cstdint>\nstatic constexpr uint32_t vulkan_motion_spv[] = {\n"
    + ",\n".join(
        ",".join(f"0x{v:08x}" for v in words[i:i + 8])
        for i in range(0, len(words), 8)
    ) + "\n};\n", encoding="utf-8",
)
