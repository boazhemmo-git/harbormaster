# Records raw NMEA AIS sentences from the Norwegian Coastal Administration's
# open AIS feed (https://kystverket.no, NLOD-licensed) into an NDJSON file
# preserving inter-arrival timing, so the replay source can reproduce
# realistic message pacing.
#
# Usage: python record_ais.py [duration_seconds] [output_file]
import json
import socket
import sys
import time

HOST, PORT = "153.44.253.27", 5631


def main() -> None:
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 900
    out_path = sys.argv[2] if len(sys.argv) > 2 else "ais-replay.ndjson"
    deadline = time.time() + duration
    count = 0
    with socket.create_connection((HOST, PORT), timeout=30) as sock, open(
        out_path, "w", encoding="ascii", errors="replace"
    ) as out:
        sock.settimeout(30)
        buf = b""
        while time.time() < deadline:
            chunk = sock.recv(4096)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                raw = line.decode("ascii", errors="replace").strip()
                if not raw or not raw.startswith(("!AIVDM", "!AIVDO", "!BSVDM", "!ABVDM")):
                    continue
                out.write(json.dumps({"t": round(time.time(), 3), "raw": raw}) + "\n")
                count += 1
                if count % 5000 == 0:
                    print(f"{count} sentences recorded", flush=True)
    print(f"DONE: {count} sentences -> {out_path}")


if __name__ == "__main__":
    main()
